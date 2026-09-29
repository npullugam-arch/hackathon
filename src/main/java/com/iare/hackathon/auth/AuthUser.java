package com.iare.hackathon.auth;

public record AuthUser(String uid, String name, String email, String photoUrl,
        boolean emailVerified, long createdAt, long lastSignInAt, String provider, String phoneNumber) {
    public AuthUser(String uid, String name, String email, String photoUrl, boolean emailVerified,
            long createdAt, long lastSignInAt, String provider) {
        this(uid, name, email, photoUrl, emailVerified, createdAt, lastSignInAt, provider, null);
    }
}
