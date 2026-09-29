package com.iare.hackathon.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.admin")
public record AdminProperties(String email, String password) {
    public boolean configured() { return email != null && !email.isBlank() && password != null && !password.isBlank(); }
    @Override public String toString() { return "AdminProperties[credentials=REDACTED]"; }
}
