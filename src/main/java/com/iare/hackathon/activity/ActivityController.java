package com.iare.hackathon.activity;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@EnableConfigurationProperties(ActivityProperties.class)
public class ActivityController {
 private final ActivityService service;
 public ActivityController(ActivityService service){this.service=service;}
 @GetMapping("/api/activity") public ActivityService.Snapshot activity(){return service.current();}
 @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> databaseError(){return ResponseEntity.status(503).body(Map.of("message","Platform activity could not be refreshed. Please try again shortly."));}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> unavailable(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()));}
}
