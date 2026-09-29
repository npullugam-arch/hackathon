package com.iare.hackathon.support;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SupportDtos {
    private SupportDtos() {}
    public record Ticket(UUID id,String userId,String userName,String title,String description,String status,String adminRemark,Instant createdAt,Instant updatedAt,List<String> attachments) {}
    public record Status(@NotBlank @Pattern(regexp="OPEN|IN_PROGRESS|RESOLVED|CLOSED") String status,@Size(max=5000) String remark) {}
}