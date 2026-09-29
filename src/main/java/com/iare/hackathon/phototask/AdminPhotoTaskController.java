package com.iare.hackathon.phototask;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/photo-tasks")
public class AdminPhotoTaskController {
 private final PhotoTaskService service;public AdminPhotoTaskController(PhotoTaskService service){this.service=service;}
 @GetMapping public PhotoTaskDtos.Page list(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page){return service.list(status,page);}
 @PutMapping("/{id}") public PhotoTaskDtos.Task review(@PathVariable UUID id,@Valid @RequestBody PhotoTaskDtos.Review review,Authentication auth){return service.review(id,review,auth.getName());}
 @GetMapping("/{id}/image") public ResponseEntity<Void> image(@PathVariable UUID id){return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(URI.create(service.image(id,null))).build();}
}
