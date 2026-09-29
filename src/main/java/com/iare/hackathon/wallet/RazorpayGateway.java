package com.iare.hackathon.wallet;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class RazorpayGateway {
    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);
    private final RazorpayProperties properties;
    private final RestClient client;
    public record RemoteOrder(String id, long amount, String currency) { }
    public record Payment(String id, String order_id, long amount, String currency, String status,
            boolean captured, long amount_refunded, Long created_at) {
        public Payment { if (created_at == null) created_at = 0L; }
        public Payment(String id, String order_id, long amount, String currency, String status,
                boolean captured, long amount_refunded) {
            this(id, order_id, amount, currency, status, captured, amount_refunded, 0L);
        }
    }

    @Autowired
    public RazorpayGateway(RazorpayProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory()));
    }
    private static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }
    RazorpayGateway(RazorpayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        client = builder.baseUrl("https://api.razorpay.com/v1")
                .defaultHeaders(h -> h.setBasicAuth(properties.keyId, properties.keySecret)).build();
    }
    public RemoteOrder createOrder(long amountPaise, String receipt) {
        properties.requireConfigured();
        try {
            var order = client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise, "currency", properties.currency,
                            "receipt", receipt, "partial_payment", false))
                    .retrieve().body(RemoteOrder.class);
            if (order == null || order.id() == null || !order.id().matches("order_[A-Za-z0-9]{1,64}")
                    || order.amount() != amountPaise || !properties.currency.equals(order.currency()))
                throw WalletException.unavailable("Payment provider returned an unexpected order. Please try again.");
            return order;
        } catch (RestClientException ex) { throw unavailable(ex); }
    }
    public Payment fetchPayment(String id) {
        properties.requireConfigured();
        try {
            var payment = client.get().uri("/payments/{id}", id).retrieve().body(Payment.class);
            if (payment == null) throw WalletException.unavailable("Payment status is unavailable. Please retry verification.");
            return payment;
        } catch (RestClientException ex) { throw unavailable(ex); }
    }
    private WalletException unavailable(RestClientException ex) {
        // Provider bodies and exception messages can contain sensitive information; never log them.
        var status = ex instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
        log.warn("Razorpay API request failed (HTTP {}).", status == null ? "unavailable" : status);
        if (status != null && (status == 401 || status == 403))
            return WalletException.unavailable("Payment provider credentials are invalid. Please contact support.");
        return WalletException.unavailable("Unable to confirm with the payment provider. Please try again shortly.");
    }
}
