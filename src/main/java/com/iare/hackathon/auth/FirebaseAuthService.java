package com.iare.hackathon.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.SessionCookieOptions;
import com.google.firebase.auth.UserRecord;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FirebaseAuthService {
    private final FirebaseProperties properties;
    private final FirebaseApp app;
    private final FirebaseAuth auth;

    public FirebaseAuthService(FirebaseProperties properties) throws IOException {
        this.properties = properties;
        if (!properties.enabled()) { app = null; auth = null; return; }
        GoogleCredentials credentials;
        if (properties.serviceAccountJson() != null && !properties.serviceAccountJson().isBlank()) {
            credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(
                    properties.serviceAccountJson().replace("\\n", "\n").getBytes(StandardCharsets.UTF_8)));
        } else {
            Path credentialPath = null;
            if (properties.serviceAccountPath() != null && !properties.serviceAccountPath().isBlank()) {
                credentialPath = Path.of(properties.serviceAccountPath());
            } else {
                Path localFallback = Path.of(System.getProperty("user.home"), "Downloads", "hackathon-admin.json");
                if (Files.isRegularFile(localFallback)) credentialPath = localFallback;
            }
            if (credentialPath != null) {
                try (var input = Files.newInputStream(credentialPath)) {
                    credentials = GoogleCredentials.fromStream(input);
                }
            } else {
                credentials = GoogleCredentials.getApplicationDefault();
            }
        }
        app = FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials)
                // Avoid the default Apache HTTP/2 transport's duplicate gzip decoding.
                // Keep standard JVM TLS trust and hostname verification enabled.
                .setHttpTransport(new NetHttpTransport())
                .setProjectId(properties.projectId()).build(), "hackathon-auth");
        auth = FirebaseAuth.getInstance(app);
    }
    public record VerifiedLogin(String sessionCookie, AuthUser user) { }

    public VerifiedLogin createSession(String idToken) throws FirebaseAuthException {
        requireEnabled();
        FirebaseToken token = auth.verifyIdToken(idToken, true);
        Object authTime = token.getClaims().get("auth_time");
        long now = Instant.now().getEpochSecond();
        if (!(authTime instanceof Number time))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firebase did not provide a valid authentication time. Please sign in again.");
        if (time.longValue() > now + 60)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The server clock is behind Firebase. Synchronize the server date and time, then sign in again.");
        if (now - time.longValue() > 300)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This sign-in is more than five minutes old. Sign in again; if this happens immediately, synchronize the server date and time.");
        AuthUser user = profile(token);
        String cookie = auth.createSessionCookie(idToken, SessionCookieOptions.builder()
                .setExpiresIn(properties.sessionDuration().toMillis()).build());
        return new VerifiedLogin(cookie, user);
    }
    public FirebaseToken verifySession(String cookie) throws FirebaseAuthException {
        requireEnabled();
        return auth.verifySessionCookie(cookie, true);
    }
    public AuthUser profile(FirebaseToken token) throws FirebaseAuthException {
        requireEnabled();
        // Trusted identity snapshot used to synchronize the Supabase application profile.
        UserRecord user = auth.getUser(token.getUid());
        Object firebase = token.getClaims().get("firebase");
        String provider = firebase instanceof Map<?, ?> data
                ? String.valueOf(data.get("sign_in_provider")) : "unknown";
        return new AuthUser(user.getUid(), user.getDisplayName(), user.getEmail(), user.getPhotoUrl(),
                user.isEmailVerified(), user.getUserMetadata().getCreationTimestamp(),
                user.getUserMetadata().getLastSignInTimestamp(), provider, user.getPhoneNumber());
    }
    private void requireEnabled() {
        if (auth == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Authentication is not configured yet.");
    }
    @PreDestroy
    void close() { if (app != null) app.delete(); }
}
