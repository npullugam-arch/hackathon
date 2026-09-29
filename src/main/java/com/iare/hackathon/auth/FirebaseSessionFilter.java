package com.iare.hackathon.auth;

import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

public class FirebaseSessionFilter extends OncePerRequestFilter {
    private final FirebaseAuthService firebase;
    private final SessionCookies cookies;
    public FirebaseSessionFilter(FirebaseAuthService firebase, SessionCookies cookies) {
        this.firebase = firebase; this.cookies = cookies;
    }
    // Stateless authentication must also be restored when an SSE request completes asynchronously.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/assets/") || path.equals("/api/auth/config")
                || path.equals("/api/auth/csrf") || path.equals("/api/auth/logout")
                || path.equals("/api/auth/session");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (!SessionCookies.NAME.equals(cookie.getName())) continue;
                try {
                    var token = firebase.verifySession(cookie.getValue());
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(token, null, List.of()));
                    SecurityContextHolder.setContext(context);
                } catch (FirebaseAuthException | ResponseStatusException | IllegalArgumentException ex) {
                    SecurityContextHolder.clearContext();
                    cookies.clear(response);
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }
}
