package com.iare.hackathon.phototask;

import static com.iare.hackathon.phototask.MachineRewardDtos.*;
import com.google.firebase.auth.FirebaseToken;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/machine-rewards")
public class MachineRewardController {
    private final MachineRewardService service;
    public MachineRewardController(MachineRewardService service){this.service=service;}
    @PostMapping("/{id}/claim") public ClaimResult claim(@AuthenticationPrincipal FirebaseToken token,@PathVariable UUID id){
        if(token==null||token.getUid()==null||token.getUid().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to claim your reward.");
        return service.claim(token.getUid(),id);
    }
}