package com.iare.hackathon.commerce;

import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={CommerceController.class,PurchaseWebhook.class,ProductImageController.class})
public class CommerceErrors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> rejected(ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"Unable to process this product request.":ex.getReason()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> invalid(){return Map.of("message","Check the required fields, dates and filter values.");}
    @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> duplicate(){return Map.of("message","This purchase, claim or referral already exists. Refresh to recover its status.");}
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> storage(){return Map.of("message","Product storage is temporarily unavailable. Retry the same purchase or claim safely.");}
}
