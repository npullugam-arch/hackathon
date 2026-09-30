package com.iare.hackathon.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.firebase")
public record FirebaseProperties(boolean enabled, String apiKey, String authDomain,
        String projectId, String appId, String messagingSenderId, String storageBucket,
        String serviceAccountPath, String serviceAccountJson, Duration sessionDuration, boolean secureCookie) {
    public FirebaseProperties {
        if (sessionDuration == null) sessionDuration = Duration.ofDays(5);
        if (sessionDuration.compareTo(Duration.ofMinutes(5)) < 0 || sessionDuration.compareTo(Duration.ofDays(14)) > 0)
            throw new IllegalArgumentException("Firebase session duration must be between 5 minutes and 14 days.");
        if (enabled && (blank(apiKey) || blank(authDomain) || blank(projectId) || blank(appId)))
            throw new IllegalArgumentException("Configure Firebase api-key, auth-domain, project-id and app-id before enabling authentication.");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
