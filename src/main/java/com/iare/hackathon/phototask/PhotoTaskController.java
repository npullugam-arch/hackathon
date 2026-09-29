package com.iare.hackathon.phototask;
import com.google.firebase.auth.FirebaseToken;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/photo-tasks")
public class PhotoTaskController {
 private final PhotoTaskService service;public PhotoTaskController(PhotoTaskService service){this.service=service;}
 private String uid(FirebaseToken token){if(token==null||token.getUid()==null||token.getUid().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to submit a photo task.");return token.getUid();}
 @GetMapping public PhotoTaskDtos.State state(@AuthenticationPrincipal FirebaseToken token){return service.state(uid(token));}
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public PhotoTaskDtos.Task submit(@AuthenticationPrincipal FirebaseToken token,@RequestParam UUID requestId,@RequestParam MultipartFile photo){return service.submit(uid(token),requestId,photo);}
 @GetMapping("/{id}/image") public ResponseEntity<Void> image(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(URI.create(service.image(id,uid(token)))).build();}
}
