package com.iare.hackathon.commerce;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class PurchaseWebhook {
    private final CommerceService service;
    private final java.util.concurrent.Semaphore inFlight=new java.util.concurrent.Semaphore(2);
    public PurchaseWebhook(CommerceService service){this.service=service;}
    @PostMapping("/api/purchases/razorpay/webhook") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(HttpServletRequest request)throws IOException{
        byte[] body=request.getInputStream().readNBytes(65537);
        if(body.length>65536)throw CommerceService.invalid("Webhook payload is too large.");
        // A notification is only a hint. Financial proof comes exclusively from authenticated Razorpay API reads.
        if(!inFlight.tryAcquire())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Retry this notification later.");
        try{
            var root=JsonMapper.builder().build().readTree(body);String event=root.path("event").asString();
            if(java.util.Set.of("payment.captured","order.paid","payment.failed").contains(event)){
                var payment=root.path("payload").path("payment").path("entity");service.webhookPayment(payment.path("order_id").asString(),payment.path("id").asString());
            }
        }catch(tools.jackson.core.JacksonException | NullPointerException ex){throw CommerceService.invalid("Invalid webhook event.");}
        finally{inFlight.release();}
    }
}
