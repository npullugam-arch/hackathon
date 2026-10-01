package com.iare.hackathon.admin;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AdminController {
    @GetMapping({"/admin-nanda", "/admin-nanda/login"})
    public String login(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                ? "redirect:/admin/dashboard" : "admin-login";
    }
    @GetMapping("/admin-nanda/dashboard") public String dashboard() { return "admin-dashboard"; }
    @GetMapping("/api/admin/csrf") @ResponseBody
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }
}
