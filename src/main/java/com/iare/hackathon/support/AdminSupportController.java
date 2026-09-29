package com.iare.hackathon.support;

import static com.iare.hackathon.support.SupportDtos.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/support")
public class AdminSupportController {
    private final SupportService service;public AdminSupportController(SupportService service){this.service=service;}
    @GetMapping public List<Ticket> tickets(){return service.all();}
    @GetMapping("/{id}") public Ticket detail(@PathVariable UUID id){return service.detail(id);}
    @PutMapping("/{id}") public Ticket update(@PathVariable UUID id,@Valid @RequestBody Status input){return service.update(id,input);}
}