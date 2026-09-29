package com.iare.hackathon.wallet;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RazorpayGatewayTests {
    final RazorpayProperties properties = new RazorpayProperties("rzp_test_public", "test-secret");
    @Test void sendsAuthenticatedOrderInPaiseAndReadsCapturedPayment() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new RazorpayGateway(properties, builder);
        server.expect(requestTo("https://api.razorpay.com/v1/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("rzp_test_public:test-secret".getBytes(StandardCharsets.UTF_8))))
                .andExpect(content().json("{\"amount\":12345,\"currency\":\"INR\",\"receipt\":\"receipt-1\",\"partial_payment\":false}"))
                .andRespond(withSuccess("{\"id\":\"order_A\",\"amount\":12345,\"currency\":\"INR\",\"status\":\"created\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_A")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"pay_A\",\"order_id\":\"order_A\",\"amount\":12345,\"currency\":\"INR\",\"status\":\"captured\",\"captured\":true,\"amount_refunded\":0}", MediaType.APPLICATION_JSON));
        assertEquals(12345, gateway.createOrder(12345, "receipt-1").amount());
        var payment = gateway.fetchPayment("pay_A");
        assertTrue(payment.captured()); assertEquals("order_A", payment.order_id()); assertEquals(12345, payment.amount());
        server.verify();
    }
    @Test void providerErrorsAreSafeAndDoNotExposeResponseOrCredentials() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new RazorpayGateway(properties, builder);
        server.expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withBadRequest().body("sensitive provider error test-secret"));
        var error = assertThrows(WalletException.class, () -> gateway.createOrder(10000, "receipt-1"));
        assertFalse(error.getMessage().contains("test-secret")); assertNull(error.getCause()); server.verify();
    }
    @Test void mismatchedProviderOrderIsNeverSentToCheckout() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new RazorpayGateway(properties, builder);
        server.expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withSuccess("{\"id\":\"order_A\",\"amount\":1,\"currency\":\"INR\"}", MediaType.APPLICATION_JSON));
        assertThrows(WalletException.class, () -> gateway.createOrder(10000, "receipt-1")); server.verify();
    }
}
