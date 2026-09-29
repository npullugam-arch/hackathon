package com.iare.hackathon.catalog;

import jakarta.validation.Valid;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/api/products") public Map<String, Object> products(@AuthenticationPrincipal FirebaseToken token) { String uid=uid(token); return Map.of("items", catalog.products(true), "purchases", catalog.purchasesForUser(uid), "serverTime", Instant.now()); }
    @GetMapping("/api/products/{id}") public Map<String, Object> product(@AuthenticationPrincipal FirebaseToken token, @PathVariable UUID id) { String uid=uid(token); return Map.of("product", catalog.product(id, true), "purchases", catalog.purchasesForUser(uid), "serverTime", Instant.now()); }
    private String uid(FirebaseToken token) { if(token==null || token.getUid()==null || token.getUid().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."); return token.getUid(); }
    @GetMapping("/api/advertisements/current") public Map<String,Object> advertisement() {
        return Map.of("items",catalog.advertisements(true));
    }
    @GetMapping("/api/admin/products") public List<Product> adminProducts() { return catalog.products(false); }
    @PostMapping("/api/admin/products") @ResponseStatus(HttpStatus.CREATED)
    public Product createProduct(@Valid @RequestBody ProductInput product) { return catalog.saveProduct(UUID.randomUUID(), product, true); }
    @PutMapping("/api/admin/products/{id}") public Product updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductInput product) { return catalog.saveProduct(id, product, false); }
    @DeleteMapping("/api/admin/products/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable UUID id) { catalog.deleteProduct(id); }
    @GetMapping("/api/admin/advertisements") public List<Advertisement> adminAdvertisements() { return catalog.advertisements(false); }
    @PostMapping("/api/admin/advertisements") @ResponseStatus(HttpStatus.CREATED)
    public Advertisement createAdvertisement(@Valid @RequestBody AdvertisementInput ad) { return catalog.saveAdvertisement(UUID.randomUUID(), ad, true); }
    @PutMapping("/api/admin/advertisements/{id}") public Advertisement updateAdvertisement(@PathVariable UUID id, @Valid @RequestBody AdvertisementInput ad) { return catalog.saveAdvertisement(id, ad, false); }
    @DeleteMapping("/api/admin/advertisements/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdvertisement(@PathVariable UUID id) { catalog.deleteAdvertisement(id); }

    @GetMapping("/api/machines") public Map<String,Object> machines() { return Map.of("items",catalog.machines(true)); }
    @GetMapping("/api/machines/{id}") public Machine machine(@PathVariable UUID id) { return catalog.machine(id,true); }
    @GetMapping("/api/admin/machines") public List<Machine> adminMachines() { return catalog.machines(false); }
    @PostMapping("/api/admin/machines") @ResponseStatus(HttpStatus.CREATED)
    public Machine createMachine(@Valid @RequestBody MachineInput m) { return catalog.saveMachine(UUID.randomUUID(),m,true); }
    @PutMapping("/api/admin/machines/{id}") public Machine updateMachine(@PathVariable UUID id,@Valid @RequestBody MachineInput m) { return catalog.saveMachine(id,m,false); }
    @DeleteMapping("/api/admin/machines/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMachine(@PathVariable UUID id) { catalog.deleteMachine(id); }
}
