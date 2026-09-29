package com.iare.hackathon.catalog;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogService {
    private final ObjectProvider<CatalogRepository> repositories;
    public CatalogService(ObjectProvider<CatalogRepository> repositories) { this.repositories = repositories; }
    private CatalogRepository repository() {
        var repository = repositories.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The catalog is temporarily unavailable.");
        return repository;
    }
    public Map<UUID, UUID> purchasesForUser(String uid) { return repository().purchasesForUser(uid); }
    public List<Product> products(boolean activeOnly) { return repository().products(activeOnly); }
    public Product product(UUID id, boolean activeOnly) { return repository().product(id, activeOnly).orElseThrow(CatalogService::notFound); }
    public Product saveProduct(UUID id, ProductInput p, boolean create) {
        validateImageUrl(p.imageUrl());
        if(p.minimumDailyIncome()==null || p.maximumDailyIncome()==null || p.minimumDailyIncome().signum()<=0 || p.maximumDailyIncome().signum()<=0 || !p.isValidRange())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Daily income must be positive and minimum must not exceed maximum.");
        if(p.maximumDailyIncome().multiply(java.math.BigDecimal.valueOf(p.totalClaims())).movePointRight(2).compareTo(new java.math.BigDecimal("9007199254740991"))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The earning total exceeds the supported limit.");
        if (p.discountPrice().compareTo(p.originalPrice()) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discount price cannot exceed original price.");
        return repository().saveProduct(id, p, create);
    }
    public void deleteProduct(UUID id) {
        try { if (!repository().deleteProduct(id)) throw notFound(); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This product has purchase history. Deactivate it to stop new sales.");
        }
    }
    public List<Advertisement> advertisements(boolean activeOnly) { return repository().advertisements(activeOnly); }
    public Advertisement saveAdvertisement(UUID id, AdvertisementInput ad, boolean create) {
        validateImageUrl(ad.imageUrl()); return repository().saveAdvertisement(id, ad, create);
    }
    public void deleteAdvertisement(UUID id) { if (!repository().deleteAdvertisement(id)) throw notFound(); }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "This item is no longer available."); }
    private static void validateImageUrl(String value) {
        if(value.trim().matches("/assets/product-images/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return;
        try {
            var uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid HTTPS image URL without embedded credentials.");
        }
    }

    public List<Machine> machines(boolean activeOnly) { return repository().machines(activeOnly); }
    public Machine machine(UUID id, boolean activeOnly) { return repository().machine(id,activeOnly).orElseThrow(CatalogService::notFound); }
    public Machine saveMachine(UUID id, MachineInput m, boolean create) {
        if (!m.imageUrl().trim().matches("/assets/machine-[123]\\.svg")) validateImageUrl(m.imageUrl());
        return repository().saveMachine(id,m,create);
    }
    public void deleteMachine(UUID id) {
        if(machine(id,false).taskType()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This task machine retains submission history. Deactivate it instead.");
        if(!repository().deleteMachine(id))throw notFound();
    }
}
