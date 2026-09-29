package com.iare.hackathon.catalog;
import jakarta.validation.constraints.*;
public record MachineInput(@NotBlank @Size(max=160) String name, @NotBlank @Size(max=2048) String imageUrl,
    @NotBlank @Size(max=160) String profileTitle, @NotBlank @Size(max=500) String shortDescription,
    @NotBlank @Size(max=20000) String fullDetails, boolean active) {}
