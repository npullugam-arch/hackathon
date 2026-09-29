package com.iare.hackathon.withdrawal;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={WithdrawalController.class,AdminWithdrawalController.class})
public class WithdrawalErrors {
    private static final Logger log=LoggerFactory.getLogger(WithdrawalErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null ? "Unable to process withdrawal." : ex.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid() { return Map.of("message","Check the amount, required bank fields, status, dates and pagination values."); }
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> duplicate() { return Map.of("message","This account, request or transfer reference already exists. Refresh and check the history."); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable(RuntimeException ex) {
        log.warn("Withdrawal storage operation failed ({}).",ex.getClass().getSimpleName());
        return Map.of("message","Withdrawal storage is temporarily unavailable. Retry the same request to safely recover its result.");
    }
}
