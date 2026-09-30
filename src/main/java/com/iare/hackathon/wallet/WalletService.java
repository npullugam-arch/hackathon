package com.iare.hackathon.wallet;

import static com.iare.hackathon.wallet.WalletDtos.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletService {
    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private final ObjectProvider<WalletRepository> repositories;
    private final RazorpayGateway gateway;
    private final RazorpayProperties properties;

    public WalletService(ObjectProvider<WalletRepository> repositories, RazorpayGateway gateway, RazorpayProperties properties) {
        this.repositories = repositories; this.gateway = gateway; this.properties = properties;
    }
    private WalletRepository repository() {
        var repository = repositories.getIfAvailable();
        if (repository == null) throw WalletException.unavailable("Wallet storage is temporarily unavailable. Please try again later.");
        return repository;
    }
    public Wallet wallet(String uid) {
        var repository = repository();
        return new Wallet(repository.balance(uid), "INR", properties.configured(), properties.maxAmount, repository.history(uid));
    }
    long paise(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.valueOf(100)) < 0 || amount.compareTo(properties.maxAmount) > 0)
            throw WalletException.invalid("Enter an amount between INR 100 and INR " + properties.maxAmount.toPlainString() + ".");
        try { return amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException ex) { throw WalletException.invalid("The amount must have no more than two decimal places."); }
    }
    public Checkout create(String uid, BigDecimal amount) {
        properties.requireConfigured();
        long amountPaise = paise(amount);
        var repository = repository();
        repository.balance(uid); // Require an existing account before creating anything at Razorpay.
        UUID id = UUID.randomUUID();
        var order = gateway.createOrder(amountPaise, id.toString());
        repository.saveOrder(id, uid, order); // Never expose a payable order unless it has been persisted.
        log.info("Recharge order {} created for {} paise.", order.id(), amountPaise);
        return new Checkout(properties.keyId, order.id(), order.amount(), order.currency());
    }
    public Verification verify(String uid, VerifyPayment request) {
        properties.requireConfigured();
        var repository = repository();
        var outcome = repository.atomic(() -> {
            var order = repository.lockOrder(request.orderId());
            if (!order.userId().equals(uid)) throw new WalletException(HttpStatus.NOT_FOUND, "Recharge order not found.");
            // The original server-side order and amount remain authoritative, even if JSON was manipulated.
            if (request.amount() == null || request.amount().compareTo(BigDecimal.valueOf(order.amountPaise(), 2)) != 0)
                throw WalletException.invalid("The payment amount does not match your recharge order.");
            if (!PaymentSignatures.matches((order.orderId() + "|" + request.paymentId()).getBytes(StandardCharsets.UTF_8),
                    request.signature(), properties.keySecret)) {
                log.warn("Payment signature rejected for recharge order {}.", order.orderId());
                throw WalletException.invalid("Payment verification failed. Your wallet has not been credited.");
            }
            return confirm(repository, order, request.paymentId());
        });
        if (outcome.error() != null) throw outcome.error();
        return outcome.verification();
    }
    private record Outcome(Verification verification, WalletException error) { }
    private Outcome confirm(WalletRepository repository, Order order, String paymentId) {
        if ("CREDITED".equals(order.status())) {
            if (!paymentId.equals(repository.creditedPayment(order.orderId())))
                throw WalletException.invalid("This order was credited using a different payment.");
            return new Outcome(new Verification(repository.balance(order.userId()), order.currency(), true), null);
        }
        var payment = gateway.fetchPayment(paymentId);
        validatePayment(order, paymentId, payment);
        repository.recordPaymentStatus(order, payment);
        if (!"captured".equals(payment.status()) || !payment.captured() || payment.amount_refunded() != 0)
            return new Outcome(null, new WalletException(HttpStatus.CONFLICT,
                    "Payment is not confirmed as captured. Your wallet has not been credited. Retry verification shortly; do not pay again."));
        long balance = repository.credit(order, paymentId);
        log.info("Recharge order {} verified; wallet credit of {} paise recorded in transaction.", order.orderId(), order.amountPaise());
        return new Outcome(new Verification(balance, order.currency(), false), null);
    }
    private void validatePayment(Order order, String paymentId, RazorpayGateway.Payment payment) {
        if (!paymentId.equals(payment.id()) || !order.orderId().equals(payment.order_id())
                || payment.amount() != order.amountPaise() || !order.currency().equals(payment.currency())) {
            log.warn("Payment details mismatch for recharge order {}.", order.orderId());
            throw WalletException.invalid("Payment details do not match the recharge order. Your wallet has not been credited.");
        }
    }
    public void observePayment(String uid, PaymentStatusRequest request) {
        properties.requireConfigured();
        var repository = repository();
        repository.atomic(() -> {
            var order = repository.lockOrder(request.orderId());
            if (!order.userId().equals(uid)) throw new WalletException(HttpStatus.NOT_FOUND, "Recharge order not found.");
            if ("CREDITED".equals(order.status())) return null;
            var payment = gateway.fetchPayment(request.paymentId());
            validatePayment(order, request.paymentId(), payment);
            // Provider evidence only: even captured payments cannot credit without a signed callback.
            repository.recordPaymentStatus(order, payment);
            return null;
        });
    }
}
