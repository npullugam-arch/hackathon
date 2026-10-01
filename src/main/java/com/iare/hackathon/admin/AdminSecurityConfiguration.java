package com.iare.hackathon.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http, AdminProperties properties) throws Exception {
        var encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(properties.configured() ? properties.password() : java.util.UUID.randomUUID().toString());
        var provider = new DaoAuthenticationProvider(username -> {
            if (!properties.configured() || !properties.email().equalsIgnoreCase(username))
                throw new UsernameNotFoundException("Invalid administrator credentials");
            return User.withUsername(properties.email()).password(hash).roles("ADMIN").build();
        });
        provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/admin", "/admin/**", "/admin-nanda", "/admin-nanda/**", "/api/admin/**")
                .authenticationManager(new ProviderManager(provider))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(f -> f.changeSessionId()))
                .authorizeHttpRequests(r -> r.requestMatchers("/admin", "/admin-nanda", "/admin-nanda/login", "/api/admin/csrf", "/api/admin/login").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form.loginPage("/admin-nanda").loginProcessingUrl("/api/admin/login").usernameParameter("email")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, error) -> {
                            response.setStatus(401); response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Invalid admin email or password, or admin access is not configured.\"}");
                        }))
                .logout(logout -> logout.logoutUrl("/api/admin/logout").invalidateHttpSession(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().startsWith("/api/")) {
                        response.setStatus(401); response.setContentType("application/json");
                        response.getWriter().write("{\"message\":\"Please sign in as an administrator.\"}");
                    } else response.sendRedirect("/admin-nanda");
                }))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; img-src 'self' https: data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")))
                .build();
    }
}
