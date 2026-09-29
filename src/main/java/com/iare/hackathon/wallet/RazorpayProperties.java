package com.iare.hackathon.wallet;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RazorpayProperties {
    final String keyId;
    final String keySecret;
    final String currency = "INR";
    final BigDecimal maxAmount = new BigDecimal("100000");

    public RazorpayProperties(@Value("${razorpay.key.id:}") String keyId,
            @Value("${razorpay.key.secret:}") String keySecret) {
        this.keyId = keyId; this.keySecret = keySecret;
    }

    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    void requireConfigured() {
        if (!configured()) throw WalletException.unavailable("Recharges are not configured yet. Please try again later.");
    }
}
