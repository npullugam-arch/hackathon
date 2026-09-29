package com.iare.hackathon.spin;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=SpinController.class)
public class SpinErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"Unable to process this spin.":ex.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid() { return Map.of("message","Refresh Daily Spin and submit a valid request ID and date."); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable() { return Map.of("message","We could not confirm your spin. Retry the same request to safely recover the result."); }
}
