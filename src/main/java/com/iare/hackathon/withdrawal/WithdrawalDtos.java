package com.iare.hackathon.withdrawal;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WithdrawalDtos {
    private WithdrawalDtos() {}
    public record Bank(String code, String name) {}
    public record BankInput(@NotBlank @Pattern(regexp="[A-Z]{4}") String bankCode,
            @NotBlank @Size(max=160) String holderName,
            @NotBlank @Pattern(regexp="[0-9]{9,18}") String accountNumber,
            @NotBlank @Pattern(regexp="[0-9]{9,18}") String confirmAccountNumber,
            @NotBlank @Pattern(regexp="[A-Z]{4}0[A-Z0-9]{6}") String ifsc,
            @NotBlank @Size(max=60) String nickname) {
        @Override public String toString() { return "BankInput[redacted]"; }
    }
    public record BankAccount(UUID id, String bankCode, String bankName, String holderName,
            String maskedAccountNumber, String ifsc, String nickname, boolean active) {}
    public record Create(@NotNull UUID bankAccountId, @NotNull @DecimalMin("0.01")
            @Digits(integer=10, fraction=2) BigDecimal amount, @NotNull UUID idempotencyKey) {}
    public record Action(@NotBlank @Pattern(regexp="PROCESSING|SUCCESSFUL|COMPLETED|FAILED|ERROR|REJECTED|CANCELLED|REFUNDED") String status,
            @Size(max=120) String referenceId, @Size(max=500) String adminRemark,
            @Size(max=500) String failureReason) {}
    public record Filter(@Pattern(regexp="PROCESSING|SUCCESSFUL|COMPLETED|FAILED|ERROR|REJECTED|CANCELLED|REFUNDED") String status,
            LocalDate from, LocalDate to, @Size(max=128) String user, UUID withdrawalId,
            @Min(0) @Max(100000) Integer page, @Min(1) @Max(100) Integer size,
            @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal minAmount,
            @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal maxAmount) {
        public Filter(String status,LocalDate from,LocalDate to,String user,UUID withdrawalId,Integer page,Integer size){this(status,from,to,user,withdrawalId,page,size,null,null);}
        public int pageNumber() { return page == null ? 0 : page; }
        public int pageSize() { return size == null ? 20 : size; }
    }
    public record Withdrawal(UUID id, String userId, String userName, String email, String phone,
            long amountPaise, BankAccount bankAccount, String status, String failureKind,
            String failureReason, String adminRemark, String referenceId, Instant requestedAt,
            Instant processedAt, Instant completedAt, Instant rejectedAt, Instant updatedAt) {}
    public record Summary(long processing,long completed,long refunded,long totalRequestedPaise) {}
    public record Page(List<Withdrawal> items, int page, int size, long total, long totalPages,Summary summary) {
        public Page(List<Withdrawal> items,int page,int size,long total,long totalPages){this(items,page,size,total,totalPages,new Summary(0,0,0,0));}
    }
    public record Dashboard(String userId, long totalRechargePaise, long totalWinningPaise,
            long availableWinningPaise, long reservedPaise, boolean bankSetupConfigured, List<BankAccount> bankAccounts,long availableRechargePaise,long minimumAmountPaise, Instant nextWithdrawalAt) {
        public Dashboard(String uid,long recharge,long winning,long available,long reserved,boolean configured,List<BankAccount> banks,long balance){this(uid,recharge,winning,available,reserved,configured,banks,balance,1000,null);}
        public Dashboard(String uid,long recharge,long winning,long available,long reserved,boolean configured,List<BankAccount> banks,long balance,long minimum){this(uid,recharge,winning,available,reserved,configured,banks,balance,minimum,null);}
        public Dashboard(String userId,long totalRechargePaise,long totalWinningPaise,long availableWinningPaise,long reservedPaise,boolean configured,List<BankAccount> banks){this(userId,totalRechargePaise,totalWinningPaise,availableWinningPaise,reservedPaise,configured,banks,totalRechargePaise,1000,null);}
        @com.fasterxml.jackson.annotation.JsonProperty public long currentWinningPaise(){return availableWinningPaise+reservedPaise;}
    }
    public record Snapshot(Dashboard dashboard, Page history) {}
    public record AdminWithdrawal(@com.fasterxml.jackson.annotation.JsonUnwrapped Withdrawal withdrawal, String accountNumber) {
        @Override public String toString() { return "AdminWithdrawal[redacted]"; }
    }
    public record AdminPage(List<AdminWithdrawal> items, int page, int size, long total, long totalPages, Summary summary) {}
    public record PayoutDetails(String accountNumber, String holderName, String bankName, String ifsc) {
        @Override public String toString() { return "PayoutDetails[redacted]"; }
    }
}
