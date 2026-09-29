package com.iare.hackathon.spin;

import com.google.firebase.auth.FirebaseToken;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/spin")
public class SpinController {
    private final SpinService service;
    public SpinController(SpinService service) { this.service=service; }
    private String uid(FirebaseToken token) {
        if(token==null || token.getUid()==null || token.getUid().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to use Daily Spin.");
        return token.getUid();
    }
    @GetMapping public SpinDtos.State status(@AuthenticationPrincipal FirebaseToken token) { return service.status(uid(token)); }
    @PostMapping public SpinDtos.Result spin(@AuthenticationPrincipal FirebaseToken token,@Valid @RequestBody SpinDtos.Request request) {
        return service.spin(uid(token),request);
    }
}
