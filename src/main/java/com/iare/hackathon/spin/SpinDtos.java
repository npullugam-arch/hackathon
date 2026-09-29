package com.iare.hackathon.spin;

import jakarta.validation.constraints.NotNull;
import java.time.*;
import java.util.*;

public final class SpinDtos {
    private SpinDtos() {}
    public record Request(@NotNull UUID requestId, @NotNull LocalDate spinDay) {}
    public record Prize(long amountPaise, int weight, int totalWeight) {}
    public record Reward(UUID id, String userId, UUID requestId, long amountPaise,
                         LocalDate spinDay, Instant awardedAt, UUID ledgerId) {}
    public record State(String userId, Instant serverTime, LocalDate spinDay, Instant resetsAt,
                        boolean eligible, long availableWinningPaise, List<Prize> prizes,
                        Reward todayReward, List<Reward> recentRewards) {}
    public record Result(Reward reward, boolean alreadySpun, State state) {}
}
