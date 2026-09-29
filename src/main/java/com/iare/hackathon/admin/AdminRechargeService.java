package com.iare.hackathon.admin;

import com.iare.hackathon.wallet.WalletRepository;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRechargeService {
    private final ObjectProvider<WalletRepository> repositories;
    public AdminRechargeService(ObjectProvider<WalletRepository> repositories) { this.repositories = repositories; }
    private WalletRepository repository() {
        var repository = repositories.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recharge tracking is temporarily unavailable.");
        return repository;
    }
    public AdminRechargeDtos.Page list(AdminRechargeDtos.Filter filter) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date must be after the start date.");
        return repository().adminRecharges(filter);
    }
    public AdminRechargeDtos.Recharge detail(UUID id) {
        return repository().adminRecharge(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge transaction not found."));
    }
}
