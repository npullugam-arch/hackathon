package com.iare.hackathon.wallet;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Uses the existing Razorpay credentials and order/payment gateway without changing recharge behavior. */
@Component
public class ProductPaymentGateway {
    private final RazorpayProperties properties;
    private final RazorpayGateway gateway;
    private final RestClient client;
    @Autowired
    public ProductPaymentGateway(RazorpayProperties properties,RazorpayGateway gateway) {
        this(properties,gateway,RestClient.builder().requestFactory(factory()));
    }
    ProductPaymentGateway(RazorpayProperties properties,RazorpayGateway gateway,RestClient.Builder builder) {
        this.properties=properties;this.gateway=gateway;
        client=builder.baseUrl("https://api.razorpay.com/v1").defaultHeaders(h->h.setBasicAuth(properties.keyId,properties.keySecret)).build();
    }
    private static SimpleClientHttpRequestFactory factory(){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(5));f.setReadTimeout(Duration.ofSeconds(15));return f;}
    public String keyId(){return properties.keyId;}
    public boolean configured(){return properties.configured();}
    public void verifySignature(String order,String payment,String signature){
        if(!PaymentSignatures.matches((order+"|"+payment).getBytes(StandardCharsets.UTF_8),signature,properties.keySecret))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payment signature verification failed.");
    }
    private <T> T call(Supplier<T> operation){
        if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Product checkout is not configured.");
        try{return operation.get();}catch(WalletException | RestClientException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unable to confirm the purchase with Razorpay. Retry this purchase safely.");}
    }
    public RazorpayGateway.RemoteOrder createOrRecover(long amount,String receipt){return call(()->{
        // An unexposed provider order may exist if the previous create response was lost.
        var result=client.get().uri(b->b.path("/orders").queryParam("receipt",receipt).queryParam("count",2).build()).retrieve().body(JsonNode.class);
        if(result==null || !result.path("items").isArray())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Purchase order recovery is unavailable.");
        var items=result.path("items");
        if(items.size()>1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Multiple provider orders need administrator review.");
        if(items.size()==1){var order=items.get(0);if(!receipt.equals(order.path("receipt").asString()) || amount!=order.path("amount").asLong() || !"INR".equals(order.path("currency").asString()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Provider order terms do not match this purchase.");
            return new RazorpayGateway.RemoteOrder(order.path("id").asString(),amount,"INR");}
        return gateway.createOrder(amount,receipt);
    });}
    public RazorpayGateway.Payment payment(String id){return call(()->gateway.fetchPayment(id));}
    public List<String> paymentIds(String order){return call(()->{
        var result=client.get().uri("/orders/{id}/payments",order).retrieve().body(JsonNode.class);
        if(result==null || !result.path("items").isArray())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Purchase payment recovery is unavailable.");
        var ids=new ArrayList<String>();for(var item:result.path("items")){var id=item.path("id").asString();if(id!=null && id.matches("pay_[A-Za-z0-9]{1,64}"))ids.add(id);}return ids;
    });}
}
