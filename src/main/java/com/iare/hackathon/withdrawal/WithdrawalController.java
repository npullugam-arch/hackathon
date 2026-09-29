package com.iare.hackathon.withdrawal;

import static com.iare.hackathon.withdrawal.WithdrawalDtos.*;
import com.google.firebase.auth.FirebaseToken;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/withdrawals")
public class WithdrawalController {
    private final WithdrawalService service;
    public WithdrawalController(WithdrawalService service) { this.service=service; }
    private String uid(FirebaseToken token) {
        if (token==null || token.getUid()==null || token.getUid().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in to manage withdrawals.");
        return token.getUid();
    }
    @GetMapping(value="/events",produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@AuthenticationPrincipal FirebaseToken token,
            jakarta.servlet.http.HttpServletResponse response) {
        String owner=uid(token);
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        return service.subscribe(owner);
    }
    @GetMapping("/snapshot") public Snapshot snapshot(@AuthenticationPrincipal FirebaseToken token,@Valid @ModelAttribute Filter filter) { return service.snapshot(uid(token),filter); }
    @GetMapping("/dashboard") public Dashboard dashboard(@AuthenticationPrincipal FirebaseToken token) { return service.dashboard(uid(token)); }
    @GetMapping("/banks") public List<Bank> directory(@AuthenticationPrincipal FirebaseToken token) { uid(token); return service.directory(); }
    @GetMapping("/bank-accounts") public List<BankAccount> banks(@AuthenticationPrincipal FirebaseToken token) { return service.banks(uid(token)); }
    @PostMapping("/bank-accounts") @ResponseStatus(HttpStatus.CREATED)
    public BankAccount addBank(@AuthenticationPrincipal FirebaseToken token, @Valid @RequestBody BankInput input) { return service.addBank(uid(token),input); }
    @DeleteMapping("/bank-accounts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id) { service.deactivate(uid(token),id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Withdrawal create(@AuthenticationPrincipal FirebaseToken token,@Valid @RequestBody Create input) { return service.create(uid(token),input); }
    @GetMapping public Page history(@AuthenticationPrincipal FirebaseToken token,@Valid @ModelAttribute Filter filter) { return service.list(uid(token),filter); }
    @GetMapping("/{id}") public Withdrawal detail(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id) { return service.detail(uid(token),id); }
}
