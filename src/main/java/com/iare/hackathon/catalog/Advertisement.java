package com.iare.hackathon.catalog;
import java.time.Instant;
import java.util.UUID;
public record Advertisement(UUID id, String title, String imageUrl, boolean active, Instant createdAt, Instant updatedAt) { }
