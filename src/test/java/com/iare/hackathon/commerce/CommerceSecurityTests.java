package com.iare.hackathon.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.auth.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.firebase.enabled=false","app.supabase.enabled=false"})
@AutoConfigureMockMvc
class CommerceSecurityTests {
    @Autowired MockMvc mvc;@Autowired ApplicationContext context;@MockitoBean FirebaseAuthService firebase;@MockitoBean CommerceService service;
    static final String ID="00000000-0000-4000-8000-000000000001";
    Cookie session()throws Exception{var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn("alice");when(firebase.verifySession("commerce-session")).thenReturn(token);return new Cookie(SessionCookies.NAME,"commerce-session");}
    @Test void startupHasDistinctConfigurationAndSecurityChainBeans(){assertEquals(1,context.getBeansOfType(PurchaseWebhookSecurity.class).size());assertTrue(context.containsBean("purchaseWebhookSecurity"));assertTrue(context.containsBean("purchaseWebhookFilterChain"));assertFalse(Boolean.parseBoolean(context.getEnvironment().getProperty("spring.main.allow-bean-definition-overriding","false")));assertNull(context.getEnvironment().getProperty("app.commerce.webhook-secret"));}
    @Test void claimsRequireAuthenticationAndCsrf()throws Exception{mvc.perform(post("/api/purchases/"+ID+"/claim").with(csrf())).andExpect(status().isUnauthorized());mvc.perform(post("/api/purchases/"+ID+"/claim").cookie(session())).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void claimIdentityComesFromSessionAndNoAmountIsAccepted()throws Exception{mvc.perform(post("/api/purchases/"+ID+"/claim").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"bob\",\"amount\":999999,\"date\":\"2100-01-01\"}")).andExpect(status().isOk());verify(service).claim("alice",UUID.fromString(ID));}
    @Test void adminApisRejectOrdinaryUsers()throws Exception{for(String route:List.of("/api/admin/purchases","/api/admin/product-sales","/api/admin/invitations")){mvc.perform(get(route).cookie(session())).andExpect(status().isUnauthorized());mvc.perform(get(route).with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());}verifyNoInteractions(service);}
    @Test void purchaseAndInvitationMutationsRequireCsrf()throws Exception{for(String route:List.of("/api/purchases","/api/invitations/bind"))mvc.perform(post(route).cookie(session()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());}
    @Test void webhookOnlyRequestsBackendReconciliationWithoutAnyCommerceSecret()throws Exception{mvc.perform(post("/api/purchases/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"order_A\",\"id\":\"pay_A\",\"amount\":999999}}}}" )).andExpect(status().isNoContent());verify(service).webhookPayment("order_A","pay_A");}
    @Test void performanceRequiresLoginAndUsesSessionIdentity()throws Exception{
        mvc.perform(get("/api/purchases/"+ID+"/performance")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
        mvc.perform(get("/api/purchases/"+ID+"/performance?interval=5&range=6&userId=bob").cookie(session())).andExpect(status().isOk());verify(service).performance("alice",UUID.fromString(ID),5,6);
    }

    @Test void calculatorPageUsesExistingSessionAndDoesNotCallCommerceServices() throws Exception {
        mvc.perform(get("/features/calculator")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/features/calculator").cookie(session())).andExpect(status().isOk())
            .andExpect(view().name("calculator"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"profit-form\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/profit-calculator.js")));
        verifyNoInteractions(service);
    }
}
