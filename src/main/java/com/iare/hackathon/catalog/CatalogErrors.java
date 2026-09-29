package com.iare.hackathon.catalog;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=CatalogController.class)
public class CatalogErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of("message", "Check all required fields, text lengths, dates and non-negative amounts (up to two decimal places)."));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage() { return ResponseEntity.status(503).body(Map.of("message", "The catalog could not be saved or loaded. Please try again.")); }
}
