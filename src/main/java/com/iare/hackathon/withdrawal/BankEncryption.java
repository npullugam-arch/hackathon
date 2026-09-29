package com.iare.hackathon.withdrawal;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BankEncryption {
    private final byte[] key;
    private BankKeyStore store;
    private final SecureRandom random = new SecureRandom();
    @Autowired public BankEncryption(BankKeyStore store) { this.key=null; this.store=store; }
    public BankEncryption(String value) {
        try {
            key = value.isBlank() ? null : Base64.getDecoder().decode(value);
            if (key != null && key.length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("app.withdrawal.bank-encryption-key must be a Base64-encoded 32-byte key.");
        }
    }
    public boolean configured() { return store != null || key != null; }
    private byte[] encryptionKey() { return store == null ? key : store.key(); }
    private void requireKey() {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Secure bank storage has not been configured. Please contact support.");
    }
    public String encrypt(String account, String context) {
        requireKey();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "." +
                    Base64.getEncoder().encodeToString(cipher.doFinal(account.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw unavailable(); }
    }
    public String decrypt(String value, String context) {
        requireKey();
        try {
            String[] parts = value.split("\\.");
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw unavailable(); }
    }
    public String fingerprint(String uid, String bankCode, String account) {
        requireKey();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(encryptionKey(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("bank-dedup-v1|" + uid + "|" + bankCode + "|" + account).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Secure bank details are unavailable. Please contact support.");
    }
}
