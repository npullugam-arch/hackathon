package com.iare.hackathon.catalog;
import jakarta.validation.constraints.*;
public record AdvertisementInput(@NotBlank @Size(max=160) String title,
        @NotBlank @Size(max=2048) String imageUrl, boolean active) { }
