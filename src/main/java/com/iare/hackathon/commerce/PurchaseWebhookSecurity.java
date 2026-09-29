package com.iare.hackathon.commerce;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PurchaseWebhookSecurity {
    @Bean(name="purchaseWebhookFilterChain") @Order(2) SecurityFilterChain purchaseWebhookSecurityChain(HttpSecurity http)throws Exception{
        return http.securityMatcher("/api/purchases/razorpay/webhook")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c->c.disable()).authorizeHttpRequests(r->r.anyRequest().permitAll()).build();
    }
}
