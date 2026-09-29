package com.iare.hackathon.wallet;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = WalletController.class)
public class WalletErrors {
    private static final Logger log = LoggerFactory.getLogger(WalletErrors.class);
    @ExceptionHandler(WalletException.class)
    public ResponseEntity<Map<String, String>> wallet(WalletException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("message", ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid() { return Map.of("message", "Invalid recharge details. Check the amount and payment details."); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> storage(RuntimeException ex) {
        log.error("Wallet database operation failed ({}). Retry verification for an existing payment.", ex.getClass().getSimpleName());
        return Map.of("message", "Wallet storage is temporarily unavailable. If you have paid, retry verification; do not pay again.");
    }
}
