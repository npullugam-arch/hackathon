package com.iare.hackathon.wallet;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WalletDtos {
    private WalletDtos() { }
    public record CreateOrder(@NotNull @DecimalMin("100.00") @Digits(integer = 8, fraction = 2) BigDecimal amount) { }
    // Identity is exclusively taken from the verified Firebase session, never from JSON.
    public record VerifyPayment(
            @NotBlank @Pattern(regexp = "order_[A-Za-z0-9]{1,64}") String orderId,
            @NotBlank @Pattern(regexp = "pay_[A-Za-z0-9]{1,64}") String paymentId,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String signature,
            @NotNull @DecimalMin("100.00") @Digits(integer = 8, fraction = 2) BigDecimal amount) {
        @Override public String toString() { return "VerifyPayment[redacted]"; }
    }
    public record Checkout(String keyId, String orderId, long amountPaise, String currency) { }
    public record PaymentStatusRequest(
            @NotBlank @Pattern(regexp = "order_[A-Za-z0-9]{1,64}") String orderId,
            @NotBlank @Pattern(regexp = "pay_[A-Za-z0-9]{1,64}") String paymentId) { }
    public record Transaction(UUID id, String userId, String razorpayOrderId, String razorpayPaymentId,
            long amountPaise, String currency, String paymentStatus, String transactionType,
            String direction, Instant createdAt) { }
    public record Wallet(long balancePaise, String currency, boolean rechargeEnabled, BigDecimal maxAmount,
            List<Transaction> transactions) { }
    public record Verification(long balancePaise, String currency, boolean alreadyCredited) { }
    record Order(UUID id, String userId, String orderId, long amountPaise, String currency, String status) { }
}
