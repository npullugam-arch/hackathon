package com.iare.hackathon.wallet;

import com.google.firebase.auth.FirebaseToken;
import static com.iare.hackathon.wallet.WalletDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {
    private final WalletService service;
    public WalletController(WalletService service) { this.service = service; }
    private String uid(FirebaseToken token) {
        if (token == null || token.getUid() == null || token.getUid().isBlank())
            throw new WalletException(HttpStatus.UNAUTHORIZED, "Please sign in to use your wallet.");
        return token.getUid();
    }
    @GetMapping
    public Wallet wallet(@AuthenticationPrincipal FirebaseToken token) { return service.wallet(uid(token)); }
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Checkout create(@AuthenticationPrincipal FirebaseToken token, @Valid @RequestBody CreateOrder request) {
        return service.create(uid(token), request.amount());
    }
    @PostMapping("/verify")
    public Verification verify(@AuthenticationPrincipal FirebaseToken token, @Valid @RequestBody VerifyPayment request) {
        return service.verify(uid(token), request);
    }
    @PostMapping("/payment-status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void paymentStatus(@AuthenticationPrincipal FirebaseToken token, @Valid @RequestBody PaymentStatusRequest request) {
        service.observePayment(uid(token), request);
    }
}
