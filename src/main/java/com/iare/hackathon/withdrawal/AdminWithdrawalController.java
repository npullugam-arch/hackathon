package com.iare.hackathon.withdrawal;

import static com.iare.hackathon.withdrawal.WithdrawalDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Guarded by the existing administrator security chain and its CSRF protection. */
@RestController
@RequestMapping("/api/admin/withdrawals")
public class AdminWithdrawalController {
    private final WithdrawalService service;
    public AdminWithdrawalController(WithdrawalService service) { this.service=service; }
    @GetMapping public AdminPage list(@Valid @ModelAttribute Filter filter,Authentication auth) { return service.adminList(filter,auth.getName()); }
    @GetMapping("/{id}") public AdminWithdrawal detail(@PathVariable UUID id,Authentication auth) { return service.adminDetail(id,auth.getName()); }
    @PostMapping("/{id}/status") public Withdrawal update(@PathVariable UUID id,@Valid @RequestBody Action action,Authentication auth) {
        return service.process(id,action,auth.getName());
    }
    @PostMapping("/{id}/payout-details") public PayoutDetails payout(@PathVariable UUID id,Authentication auth) {
        return service.payoutDetails(id,auth.getName());
    }
}
