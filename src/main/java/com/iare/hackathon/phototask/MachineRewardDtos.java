package com.iare.hackathon.phototask;

import java.time.Instant;
import java.util.UUID;

public final class MachineRewardDtos {
    private MachineRewardDtos() {}
    public record Reward(UUID id,String type,UUID sourceId,long amountPaise,String status,UUID ledgerId,Instant createdAt,Instant claimedAt) {}
    public record ClaimResult(Reward reward,long availableWinningPaise,boolean alreadyClaimed) {}
}