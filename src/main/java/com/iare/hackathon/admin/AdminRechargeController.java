package com.iare.hackathon.admin;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** All mappings are guarded by the existing ROLE_ADMIN security chain. */
@RestController
@RequestMapping("/api/admin/recharges")
public class AdminRechargeController {
    private static final Logger log = LoggerFactory.getLogger(AdminRechargeController.class);
    private final AdminRechargeService service;
    public AdminRechargeController(AdminRechargeService service) { this.service = service; }
    @GetMapping public AdminRechargeDtos.Page list(@Valid @ModelAttribute AdminRechargeDtos.Filter filter) {
        return service.list(filter);
    }
    @GetMapping("/{id}") public AdminRechargeDtos.Recharge detail(@PathVariable UUID id) { return service.detail(id); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason() == null ? "Unable to load recharge transactions." : ex.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid() { return Map.of("message", "Check the status, date range, filter lengths and pagination values."); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(RuntimeException ex) {
        log.warn("Admin recharge query failed ({}).", ex.getClass().getSimpleName());
        return Map.of("message", "Recharge transactions could not be loaded. Please try again.");
    }
}
