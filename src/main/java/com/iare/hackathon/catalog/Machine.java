package com.iare.hackathon.catalog;
import java.time.Instant;
import java.util.UUID;
public record Machine(UUID id, String name, String imageUrl, String profileTitle, String shortDescription,
    String fullDetails, boolean active, Instant createdAt, Instant updatedAt, String taskType) {}
