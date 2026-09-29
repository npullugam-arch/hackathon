package com.iare.hackathon.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthPageController {
    @GetMapping({"/", "/login", "/register", "/forgot-password"})
    public String authentication(Authentication authentication,@RequestParam(required=false) String ref) {
        if(authentication!=null&&ref!=null&&ref.matches("[a-fA-F0-9]{32}"))return "redirect:/features/invitation?ref="+ref;
        return authentication != null ? "redirect:/dashboard" : "auth";
    }
    @GetMapping("/dashboard")
    public String dashboard() { return "dashboard"; }
}
