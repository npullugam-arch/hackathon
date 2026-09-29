package com.iare.hackathon.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookies {
    public static final String NAME = "hackathon_session";
    private final FirebaseProperties properties;
    public SessionCookies(FirebaseProperties properties) { this.properties = properties; }
    public void write(HttpServletResponse response, String value) { set(response, value, properties.sessionDuration()); }
    public void clear(HttpServletResponse response) { set(response, "", Duration.ZERO); }
    private void set(HttpServletResponse response, String value, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, value)
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax")
                .path("/").maxAge(age).build().toString());
    }
}
