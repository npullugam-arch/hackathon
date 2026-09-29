package com.iare.hackathon.commerce;

import static com.iare.hackathon.commerce.CommerceDtos.*;
import com.google.firebase.auth.FirebaseToken;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CommerceController {
    private final CommerceService service;
    public CommerceController(CommerceService service){this.service=service;}
    private String uid(FirebaseToken token){if(token==null||token.getUid()==null||token.getUid().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in to use your product account.");return token.getUid();}
    @PostMapping("/api/purchases") public Checkout buy(@AuthenticationPrincipal FirebaseToken token,@Valid @RequestBody Buy input){return service.buy(uid(token),input);}
    @PostMapping("/api/purchases/{id}/checkout") public Checkout checkout(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){return service.checkout(uid(token),id);}
    @PostMapping("/api/purchases/{id}/verify") public Purchase verify(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id,@Valid @RequestBody Verify input){return service.verify(uid(token),id,input);}
    @PostMapping("/api/purchases/{id}/reconcile") public Purchase reconcile(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){return service.reconcile(uid(token),id);}
    @PostMapping("/api/purchases/{id}/claim") public ClaimResult claim(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){return service.claim(uid(token),id);}
    @GetMapping("/api/purchases") public Page<Purchase> purchases(@AuthenticationPrincipal FirebaseToken token,@Valid @ModelAttribute Filter filter){return service.purchases(uid(token),filter);}
    @GetMapping("/api/purchases/{id}/performance") public SimulatedPerformance performance(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id,
            @RequestParam(defaultValue="15") int interval,@RequestParam(defaultValue="24") int range){return service.performance(uid(token),id,interval,range);}
    @GetMapping("/api/purchases/{id}") public PurchaseDetail detail(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){return service.detail(uid(token),id);}
    @GetMapping("/api/invitations") public InvitationDashboard invitations(@AuthenticationPrincipal FirebaseToken token,@Valid @ModelAttribute ReferralFilter filter){return service.invitations(uid(token),filter);}
    @PostMapping("/api/invitations/bind") public InvitationDashboard bind(@AuthenticationPrincipal FirebaseToken token,@Valid @RequestBody Bind input){return service.bind(uid(token),input);}
    @GetMapping("/api/team") public TeamNode team(@AuthenticationPrincipal FirebaseToken token){return service.team(uid(token));}
    @GetMapping("/api/admin/purchases") public Page<Purchase> adminPurchases(@Valid @ModelAttribute Filter filter){return service.purchases(null,filter);}
    @GetMapping("/api/admin/purchases/{id}") public PurchaseDetail adminDetail(@PathVariable UUID id){return service.detail(null,id);}
    @GetMapping("/api/admin/product-sales") public Page<ProductSales> sales(@Valid @ModelAttribute Filter filter){return service.sales(filter);}
    @GetMapping("/api/admin/invitations") public Page<Invitation> adminInvitations(@Valid @ModelAttribute ReferralFilter filter){return service.adminInvitations(filter);}
}
