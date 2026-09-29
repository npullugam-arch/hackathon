package com.iare.hackathon.wallet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class PaymentSignatures {
    private PaymentSignatures() { }
    static boolean matches(byte[] payload, String signature, String secret) {
        if (secret == null || secret.isBlank() || signature == null || !signature.matches("[a-fA-F0-9]{64}")) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(signature));
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("Payment signature verification unavailable", ex); }
    }
}
