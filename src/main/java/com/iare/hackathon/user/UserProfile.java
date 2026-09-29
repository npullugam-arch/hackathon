package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import java.time.Instant;

/** Persisted application profile. No passwords, Firebase tokens or session cookies are stored. */
public record UserProfile(String firebaseUid, String email, String displayName, String photoUrl,
        boolean emailVerified, String authProvider, long firebaseCreatedAt, long lastSignInAt,
        Instant createdAt, Instant updatedAt, String phoneNumber) {
    public UserProfile(String firebaseUid, String email, String displayName, String photoUrl,
            boolean emailVerified, String authProvider, long firebaseCreatedAt, long lastSignInAt,
            Instant createdAt, Instant updatedAt) {
        this(firebaseUid, email, displayName, photoUrl, emailVerified, authProvider, firebaseCreatedAt,
                lastSignInAt, createdAt, updatedAt, null);
    }
    public AuthUser toAuthUser() {
        return new AuthUser(firebaseUid, displayName, email, photoUrl, emailVerified,
                firebaseCreatedAt, lastSignInAt, authProvider, phoneNumber);
    }
}
