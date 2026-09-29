package com.iare.hackathon.catalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record Product(UUID id, String title, String imageUrl, BigDecimal originalPrice, BigDecimal discountPrice,
        int totalClaims, BigDecimal dailyIncome, BigDecimal totalEarnings, String description,
        boolean active, Instant createdAt, Instant updatedAt, long soldCount, BigDecimal minimumDailyIncome, String countryName, String countryUrl, String tag) {
    @com.fasterxml.jackson.annotation.JsonProperty public boolean soldOut(){return !active;}
    @com.fasterxml.jackson.annotation.JsonProperty public BigDecimal maximumDailyIncome(){return dailyIncome;}
    @com.fasterxml.jackson.annotation.JsonProperty
    public BigDecimal expectedProfit() { return dailyIncome.multiply(BigDecimal.valueOf(totalClaims)).subtract(discountPrice); }
}
