package com.iare.hackathon.commerce;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class CommerceDtos {
    private CommerceDtos() {}
    public record Buy(@NotNull UUID productId) {}
    public record Verify(@NotBlank @Pattern(regexp="pay_[A-Za-z0-9]{1,64}") String paymentId,
            @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String signature) {
        @Override public String toString(){return "Verify[redacted]";}
    }
    public record Bind(@NotBlank @Pattern(regexp="[A-Fa-f0-9]{32}") String code) {}
    public record Filter(@Min(0) @Max(100000) Integer page,@Min(1) @Max(100) Integer size,
            UUID productId,@Size(max=128) String user,LocalDate from,LocalDate to,
            @Pattern(regexp="ACTIVE|COMPLETED|UNPAID") String status,
            @Pattern(regexp="CREATING|PENDING|FAILED|PAID") String paymentStatus,
            @Pattern(regexp="newest|oldest|amount_desc|amount_asc") String sort) {
        public int pageNumber(){return page==null?0:page;} public int pageSize(){return size==null?20:size;}
    }
    public record ReferralFilter(@Min(0) @Max(100000) Integer page,@Min(1) @Max(100) Integer size,
            @Size(max=128) String inviter,@Size(max=128) String invitee,@Size(max=32) String code,
            LocalDate from,LocalDate to,@Pattern(regexp="PENDING|PAID") String purchaseStatus,
            @Pattern(regexp="LOCKED|PENDING|COMPLETED|CLAIMED|CREDITED") String rewardStatus,@Pattern(regexp="newest|oldest") String sort) {
        public int pageNumber(){return page==null?0:page;} public int pageSize(){return size==null?20:size;}
    }
    public record Page<T>(List<T> items,int page,int size,long total,long totalPages) {}
    public record Purchase(UUID id,String userId,String userName,String email,UUID productId,String productTitle,String imageUrl,
            long purchaseAmountPaise,long dailyIncomePaise,int durationDays,String claimZone,LocalTime claimTime,
            String orderId,String paymentId,String paymentStatus,Instant purchaseDate,Instant startDate,Instant endDate,
            Instant configuredStartAt,Instant createdAt,String status,int completedDays,int remainingDays,int claimedDays,
            long claimedIncomePaise,long totalExpectedPaise,long expectedProfitPaise,long currentEarningsPaise,
            long claimablePaise,long missedIncomePaise,long remainingFuturePaise,Instant nextClaimAt,Integer claimDay,long minimumDailyIncomePaise,long maximumDailyIncomePaise,long todayProfitPaise,long finalDailyProfitPaise,Instant serverTime,DailyEarningCycle dailyEarningCycle) {
        @com.fasterxml.jackson.annotation.JsonProperty public java.math.BigDecimal progressPercent() {
            if(totalExpectedPaise==0)return status.equals("COMPLETED")?new java.math.BigDecimal("100"):java.math.BigDecimal.ZERO;
            return java.math.BigDecimal.valueOf(claimedDays).multiply(new java.math.BigDecimal("100"))
                    .divide(java.math.BigDecimal.valueOf(durationDays),2,java.math.RoundingMode.DOWN);
        }
        @com.fasterxml.jackson.annotation.JsonProperty public long remainingEarningsPaise(){return remainingFuturePaise+claimablePaise;}
    }
        public record Checkout(Purchase purchase,long rechargeBalancePaise,boolean purchased) {}
    public record ClaimResult(UUID claimId,UUID ledgerId,int dayNumber,long amountPaise,boolean alreadyClaimed,Purchase purchase) {}
    public record Claim(UUID id,int dayNumber,long amountPaise,Instant eligibleAt,Instant claimedAt,UUID ledgerId) {}
    public record ProductSales(UUID productId,String productTitle,long soldCount,long purchaserCount,long salesPaise,
            long claimedPaise,long unclaimedPaise,long activePurchases,long completedPurchases) {}
    public record DailyEarningCycle(LocalDate date,Instant startsAt,Instant endsAt,Instant settlesAt,
            long finalAmountPaise,long accruedPaise,java.math.BigDecimal progressPercent,String status) {}
    public record PerformanceCandle(Instant startsAt,Instant endsAt,double open,double high,double low,double close,boolean complete) {}
    public record SimulatedPerformance(String label,String units,Instant serverTime,DailyEarningCycle cycle,
            int intervalMinutes,int rangeHours,List<PerformanceCandle> candles) {}
    public record PurchaseDetail(Purchase purchase,List<Claim> claims,UUID paymentTransactionId) {}
    public record Invitation(UUID id,String inviterId,String inviterName,String inviteeId,String inviteeName,String referralCode,
            Instant registrationDate,Instant boundAt,String purchaseStatus,UUID purchaseId,String productTitle,Long purchaseAmountPaise,
            String rewardStatus,long inviterRewardPaise,long inviteeRewardPaise,UUID inviterLedgerId,UUID inviteeLedgerId) {}
        public record InviterProfile(String userId,String name,String photoUrl) {}
        public record InvitationDashboard(String userId,String code,String boundCode,InviterProfile inviter,long totalInvited,long successfulInvitations,
            long totalRewardsPaise,long availableWinningPaise,boolean canBind,boolean eligibleForReferAndEarn,List<com.iare.hackathon.phototask.MachineRewardDtos.Reward> machineRewards,Page<Invitation> invitations) {}
        public record TeamNode(String userId,String name,String photoUrl,int level,List<TeamNode> children) {}
}
