package com.iare.hackathon.commerce;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommerceConfiguration {
    @Bean Clock commerceClock() { return Clock.systemUTC(); }
    @Bean ClaimSchedule claimSchedule(@Value("${app.commerce.claim-zone:Asia/Kolkata}") String zone,
            @Value("${app.commerce.claim-time:00:00}") String time) {
        return new ClaimSchedule(ZoneId.of("Asia/Kolkata"),LocalTime.MIDNIGHT);
    }
    public record ClaimSchedule(ZoneId zone, LocalTime time) {}
}
