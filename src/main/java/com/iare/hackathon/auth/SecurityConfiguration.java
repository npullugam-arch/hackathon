package com.iare.hackathon.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, FirebaseAuthService firebase,
            SessionCookies cookies, FirebaseProperties properties) throws Exception {
        var csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/"));
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/", "/login", "/register", "/forgot-password", "/assets/**", "/error", "/information/**",
                                "/api/auth/config", "/api/auth/csrf", "/api/auth/session", "/api/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) -> {
                    if (request.getRequestURI().startsWith("/api/")) {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"message\":\"Your session has expired. Please sign in again.\"}");
                    } else response.sendRedirect("/login");
                }))
                .headers(headers -> headers
                        .addHeaderWriter((request, response) -> {
                            String policy = "default-src 'self'; script-src 'self' https://www.gstatic.com https://apis.google.com; style-src 'self'; img-src 'self' data: https:; connect-src 'self' https://*.googleapis.com https://*.firebaseapp.com https://*.gstatic.com; frame-src https://*.firebaseapp.com https://accounts.google.com; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
                            if (request.getRequestURI().equals(request.getContextPath() + "/features/recharge")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/products/")
                                    || request.getRequestURI().equals(request.getContextPath() + "/features/my-product")) {
                                // Checkout injects a positioned iframe. Limit its origins/style attributes to this page.
                                policy = policy.replace("https://apis.google.com;", "https://apis.google.com https://checkout.razorpay.com;")
                                        .replace("style-src 'self';", "style-src 'self'; style-src-attr 'unsafe-inline';")
                                        .replace("https://*.gstatic.com;", "https://*.gstatic.com https://*.razorpay.com;")
                                        .replace("https://accounts.google.com;", "https://accounts.google.com https://*.razorpay.com;");
                            }
                            response.setHeader("Content-Security-Policy", policy);
                        })
                        .addHeaderWriter((request, response) -> response.setHeader("Cross-Origin-Opener-Policy", "same-origin-allow-popups")))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .addFilterBefore(new FirebaseSessionFilter(firebase, cookies), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
