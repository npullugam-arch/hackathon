package com.iare.hackathon.admin;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class AdminRechargeDtos {
    private AdminRechargeDtos() { }
    public record Filter(@Min(0) @Max(100000) Integer page, @Min(1) @Max(100) Integer size,
            @Pattern(regexp = "SUCCESSFUL|FAILED|PENDING") String status,
            @Size(max = 160) String name, @Size(max = 254) String email, @Size(max = 32) String phone,
            @Size(max = 80) String transactionId, @Size(max = 80) String paymentId,
            @Size(max = 254) String search,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        public Filter {
            page = page == null ? 0 : page;
            size = size == null ? 25 : size;
            status = clean(status); name = clean(name); email = clean(email); phone = clean(phone);
            transactionId = clean(transactionId); paymentId = clean(paymentId); search = clean(search);
        }
        private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    }
    public record Summary(long totalAttempts, long successful, long failed, long pending,
            BigDecimal successfulAmountPaise, BigDecimal walletCreditedPaise, String currency) { }
    public record Recharge(UUID rechargeId, UUID transactionId, UUID walletTransactionId,
            String userId, String userName, String userEmail, String userPhone,
            long amountPaise, String currency, String razorpayOrderId, String razorpayPaymentId,
            String status, String providerStatus, String transactionType, long walletCreditedPaise,
            Instant createdAt, Instant creditedAt, Instant statusCheckedAt) { }
    public record Page(List<Recharge> items, Summary summary, long total, int page, int size,
            long totalPages, Instant refreshedAt) { }
}
