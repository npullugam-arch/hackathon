package com.iare.hackathon.auth;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.user.UserProfileService;
import com.iare.hackathon.user.ProfileStorageUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final FirebaseProperties properties;
    private final FirebaseAuthService firebase;
    private final SessionCookies cookies;
    private final UserProfileService profiles;
    public AuthController(FirebaseProperties properties, FirebaseAuthService firebase, SessionCookies cookies,
            UserProfileService profiles) {
        this.properties = properties; this.firebase = firebase; this.cookies = cookies; this.profiles = profiles;
    }
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", properties.enabled(), "firebase", Map.of(
                "apiKey", properties.apiKey(), "authDomain", properties.authDomain(),
                "projectId", properties.projectId(), "appId", properties.appId(),
                "messagingSenderId", properties.messagingSenderId(), "storageBucket", properties.storageBucket()));
    }
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    public record SessionRequest(@NotBlank @Size(max = 10000) String idToken) { }
    @PostMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@Valid @RequestBody SessionRequest request, HttpServletResponse response) throws FirebaseAuthException {
        var login = firebase.createSession(request.idToken());
        profiles.synchronize(login.user());
        // No cookie is issued if database persistence fails; retrying safely upserts the same UID.
        cookies.write(response, login.sessionCookie());
    }
    @GetMapping("/me")
    public AuthUser me(@AuthenticationPrincipal FirebaseToken token) throws FirebaseAuthException {
        var stored = profiles.findByUid(token.getUid());
        // Existing Firebase sessions created before this integration are migrated on first access.
        return stored.isPresent() ? stored.get() : profiles.synchronize(firebase.profile(token));
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletResponse response) { cookies.clear(response); }
    @ExceptionHandler(ProfileStorageUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailableStorage() {
        return Map.of("message", "Account storage is temporarily unavailable. Please try again later.");
    }
    @ExceptionHandler(FirebaseAuthException.class)
    public ResponseEntity<Map<String, String>> invalidAuthentication(FirebaseAuthException error) {
        String code = error.getAuthErrorCode() == null ? "UNKNOWN" : error.getAuthErrorCode().name();
        log.warn("Firebase session request failed: authCode={}, serviceCode={}", code, error.getErrorCode());
        String message = switch (code) {
            case "EXPIRED_ID_TOKEN" -> "Your Firebase token expired. Please sign in again.";
            case "REVOKED_ID_TOKEN", "REVOKED_SESSION_COOKIE" -> "Your sign-in was revoked. Please sign in again.";
            case "USER_DISABLED" -> "This account has been disabled. Contact your administrator.";
            case "USER_NOT_FOUND" -> "Your Firebase account could not be found. Please sign in again.";
            case "INVALID_ID_TOKEN" -> "Firebase rejected the sign-in token. Check that the browser and backend use the same Firebase project and the server clock is correct.";
            case "CERTIFICATE_FETCH_FAILED" -> "The server could not download Firebase verification certificates. Check the server connection and try again.";
            default -> "Firebase could not create your session. Check the server Firebase credentials and permissions. Reference: " + code + ".";
        };
        boolean serverFailure = code.equals("CERTIFICATE_FETCH_FAILED") || code.equals("CONFIGURATION_NOT_FOUND")
                || code.equals("UNKNOWN") && error.getErrorCode() != null;
        return ResponseEntity.status(serverFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", message, "code", code));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejectedSession(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "Unable to complete sign-in." : error.getReason()));
    }
}
