package com.iare.hackathon.wallet;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.auth.FirebaseAuthService;
import com.iare.hackathon.auth.SessionCookies;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.firebase.enabled=false", "app.supabase.enabled=false"})
@AutoConfigureMockMvc
class WalletSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuthService firebase;
    @MockitoBean WalletService wallet;
    Cookie session() throws Exception {
        var token = mock(FirebaseToken.class); when(token.getUid()).thenReturn("alice");
        when(firebase.verifySession("verified-session")).thenReturn(token);
        return new Cookie(SessionCookies.NAME, "verified-session");
    }
    @Test void walletAndRechargeRequireAuthentication() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/wallet/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/wallet/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(wallet);
    }
    @Test void financialWritesRequireCsrfEvenWithValidSession() throws Exception {
        for (String path : new String[]{"/api/wallet/orders", "/api/wallet/verify", "/api/wallet/payment-status"})
            mvc.perform(post(path).cookie(session()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(wallet);
    }
    @Test void usesFirebaseIdentityAndReturnsOnlyPublicCheckoutConfiguration() throws Exception {
        when(wallet.create(eq("alice"), any())).thenReturn(new WalletDtos.Checkout("rzp_test_public", "order_A", 10000, "INR"));
        mvc.perform(post("/api/wallet/orders").with(csrf()).cookie(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"userId\":\"bob\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amountPaise").value(10000))
                .andExpect(jsonPath("$.keyId").value("rzp_test_public"))
                .andExpect(content().string(not(containsString("secret"))));
        verify(wallet).create("alice", new BigDecimal("100"));
    }
    @Test void validatesRequestsBeforeCallingService() throws Exception {
        for (String amount : new String[]{"0", "-1", "1.001", "null", "\"invalid\""})
            mvc.perform(post("/api/wallet/orders").with(csrf()).cookie(session()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":" + amount + "}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/wallet/verify").with(csrf()).cookie(session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"order_A\",\"paymentId\":\"pay_A\",\"signature\":\"bad\",\"amount\":100}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(wallet);
    }
    @Test void verificationUsesSessionIdentityAndReturnsConfirmedBalance() throws Exception {
        when(wallet.verify(eq("alice"), any())).thenReturn(new WalletDtos.Verification(10000, "INR", false));
        mvc.perform(post("/api/wallet/verify").with(csrf()).cookie(session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"order_A\",\"paymentId\":\"pay_A\",\"signature\":\"" + "a".repeat(64) + "\",\"amount\":100,\"userId\":\"bob\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balancePaise").value(10000));
        verify(wallet).verify(eq("alice"), any());
    }
    @Test void walletReadsAreScopedToTheSignedInUser() throws Exception {
        when(wallet.wallet("alice")).thenReturn(new WalletDtos.Wallet(12345, "INR", true, new BigDecimal("100000"), List.of()));
        mvc.perform(get("/api/wallet?userId=bob").cookie(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(12345));
        verify(wallet).wallet("alice");
    }
    @Test void rechargePageRendersAndAllowsCheckoutThroughCsp() throws Exception {
        mvc.perform(get("/features/recharge").cookie(session())).andExpect(status().isOk())
                .andExpect(view().name("recharge")).andExpect(content().string(containsString("id=\"recharge-amount\"")))
                .andExpect(header().string("Content-Security-Policy", containsString("https://checkout.razorpay.com")));
    }
    @Test void statusObservationRequiresSessionAndUsesItsIdentity() throws Exception {
        String body = "{\"orderId\":\"order_A\",\"paymentId\":\"pay_A\",\"userId\":\"bob\",\"status\":\"FAILED\"}";
        mvc.perform(post("/api/wallet/payment-status").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/wallet/payment-status").with(csrf()).cookie(session()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        verify(wallet).observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_A"));
    }
}
