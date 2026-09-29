package com.iare.hackathon.support;

import static com.iare.hackathon.support.SupportDtos.*;
import com.google.firebase.auth.FirebaseToken;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportService service;public SupportController(SupportService service){this.service=service;}
    private String uid(FirebaseToken token){if(token==null||token.getUid()==null||token.getUid().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in to contact support.");return token.getUid();}
    @GetMapping public List<Ticket> tickets(@AuthenticationPrincipal FirebaseToken token){return service.tickets(uid(token));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Ticket create(@AuthenticationPrincipal FirebaseToken token,@RequestParam String title,@RequestParam String description,@RequestParam(name="screenshots",required=false) MultipartFile[] screenshots){return service.create(uid(token),title,description,screenshots);}
}