package com.iare.hackathon.catalog;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record ProductInput(@NotBlank @Size(max=160) String title,
        @NotBlank @Size(max=2048) String imageUrl,
        @NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2) BigDecimal originalPrice,
        @NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2) BigDecimal discountPrice,
        @Min(1) @Max(3650) int totalClaims,
        @NotNull @DecimalMin("0.01") @Digits(integer=12, fraction=2) BigDecimal minimumDailyIncome,
        @NotNull @DecimalMin("0.01") @Digits(integer=12, fraction=2) BigDecimal maximumDailyIncome,
        @NotBlank @Size(max=20000) String description, boolean active, @Size(max=120) String countryName, @Size(max=2048) String countryUrl, @Size(max=40) String tag) {
    public ProductInput(String title,String imageUrl,BigDecimal originalPrice,BigDecimal discountPrice,int totalClaims,BigDecimal dailyIncome,String description,boolean active) {
        this(title,imageUrl,originalPrice,discountPrice,totalClaims,dailyIncome,dailyIncome,description,active,null,null,null);
    }
    public ProductInput(String title,String imageUrl,BigDecimal originalPrice,BigDecimal discountPrice,int totalClaims,BigDecimal minimumDailyIncome,BigDecimal maximumDailyIncome,String description,boolean active) { this(title,imageUrl,originalPrice,discountPrice,totalClaims,minimumDailyIncome,maximumDailyIncome,description,active,null,null,null); }
    public BigDecimal dailyIncome(){return maximumDailyIncome;}
    @AssertTrue(message="Minimum daily income must not exceed maximum daily income")
    public boolean isValidRange(){return minimumDailyIncome==null || maximumDailyIncome==null || minimumDailyIncome.compareTo(maximumDailyIncome)<=0;}
}
