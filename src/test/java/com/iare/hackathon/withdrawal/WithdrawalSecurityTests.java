package com.iare.hackathon.withdrawal;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.auth.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.firebase.enabled=false","app.supabase.enabled=false"})
@AutoConfigureMockMvc
class WithdrawalSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuthService firebase;
    @MockitoBean WithdrawalService withdrawals;
    static final String ID="00000000-0000-4000-8000-000000000001";
    Cookie session() throws Exception {
        var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn("alice");when(firebase.verifySession("withdrawal-test-session")).thenReturn(token);
        return new Cookie(SessionCookies.NAME,"withdrawal-test-session");
    }
    @Test void withdrawalReadsAndWritesRequireLogin() throws Exception {
        for(String path:List.of("/api/withdrawals","/api/withdrawals/dashboard","/api/withdrawals/events","/api/withdrawals/snapshot","/api/withdrawals/banks","/api/withdrawals/bank-accounts","/api/withdrawals/"+ID))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/withdrawals").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        verifyNoInteractions(withdrawals);
    }
    @Test void userCannotAccessAdminWithdrawals() throws Exception {
        mvc.perform(get("/api/admin/withdrawals").cookie(session())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/withdrawals/"+ID+"/status").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCESSFUL\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/withdrawals/"+ID+"/payout-details").with(user("ordinary").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(withdrawals);
    }
    @Test void userFinancialMutationsRequireCsrf() throws Exception {
        for(String path:List.of("/api/withdrawals","/api/withdrawals/bank-accounts"))
            mvc.perform(post(path).cookie(session()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(delete("/api/withdrawals/bank-accounts/"+ID).cookie(session())).andExpect(status().isForbidden());
        verifyNoInteractions(withdrawals);
    }
    @Test void adminMutationsAndRevealRequireCsrf() throws Exception {
        for(String suffix:List.of("/status","/payout-details"))
            mvc.perform(post("/api/admin/withdrawals/"+ID+suffix).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        verifyNoInteractions(withdrawals);
    }
    @Test void dashboardUsesFirebaseIdentityAndIsNotCached() throws Exception {
        when(withdrawals.dashboard("alice")).thenReturn(new WithdrawalDtos.Dashboard("alice",100000,225000,200000,25000,true,List.of()));
        mvc.perform(get("/api/withdrawals/dashboard?userId=bob").cookie(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableWinningPaise").value(200000)).andExpect(header().string("Cache-Control",containsString("no-store")));
        verify(withdrawals).dashboard("alice");
        mvc.perform(get("/api/withdrawals/snapshot?userId=bob").cookie(session())).andExpect(status().isOk());
        verify(withdrawals).snapshot(eq("alice"),any());
    }
    @Test void validatesAmountsAndBankDetailsBeforeService() throws Exception {
        for(String amount:List.of("0","-1","0.001","100.001","null","\"invalid\""))
            mvc.perform(post("/api/withdrawals").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bankAccountId\":\""+ID+"\",\"idempotencyKey\":\""+ID+"\",\"amount\":"+amount+"}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/withdrawals/bank-accounts").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankCode\":\"SBIN\",\"holderName\":\"Test\",\"accountNumber\":\"123\",\"confirmAccountNumber\":\"123\",\"ifsc\":\"bad\",\"nickname\":\"Main\"}" )).andExpect(status().isBadRequest());
        verifyNoInteractions(withdrawals);
    }
    @Test void requestIgnoresForgedUserAndBalanceFields() throws Exception {
        mvc.perform(post("/api/withdrawals").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankAccountId\":\""+ID+"\",\"idempotencyKey\":\""+ID+"\",\"amount\":100,\"userId\":\"bob\",\"availableWinningPaise\":999999}" )).andExpect(status().isCreated());
        verify(withdrawals).create(eq("alice"),any());
        mvc.perform(get("/api/withdrawals/"+ID+"?userId=bob").cookie(session())).andExpect(status().isOk());verify(withdrawals).detail("alice",UUID.fromString(ID));
    }
    @Test void adminUsesAuthenticatedActorAndMaskedList() throws Exception {
        when(withdrawals.adminList(any(),eq("reviewer"))).thenReturn(new WithdrawalDtos.AdminPage(List.of(),0,20,0,0,new WithdrawalDtos.Summary(0,0,0,0)));
        mvc.perform(get("/api/admin/withdrawals?status=PROCESSING").with(user("reviewer").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/withdrawals/"+ID+"/status").with(user("reviewer").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\",\"failureReason\":\"No transfer\",\"actor\":\"attacker\"}" )).andExpect(status().isOk());
        verify(withdrawals).process(eq(UUID.fromString(ID)),any(),eq("reviewer"));
    }
    @Test void sensitiveRevealIsExplicitAdminOnlyAndNotCached() throws Exception {
        when(withdrawals.payoutDetails(UUID.fromString(ID),"reviewer")).thenReturn(new WithdrawalDtos.PayoutDetails("12345678901","Test","Bank","SBIN0000001"));
        mvc.perform(post("/api/admin/withdrawals/"+ID+"/payout-details").with(user("reviewer").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")));
        verify(withdrawals).payoutDetails(UUID.fromString(ID),"reviewer");
    }
    @Test void withdrawalAndAdminPagesRender() throws Exception {
        mvc.perform(get("/features/withdrawal").cookie(session())).andExpect(status().isOk()).andExpect(view().name("withdrawal"))
                .andExpect(content().string(containsString("id=\"withdraw-form\"")));
        mvc.perform(get("/admin/dashboard").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"withdrawals-section\"")));
    }

    @Test void adminFullAccountIsFlattenedAndUserResponseRemainsMasked() throws Exception {
        var id=UUID.fromString(ID);
        var bank=new WithdrawalDtos.BankAccount(id,"SBIN","State Bank","Test Holder","**** 8901","SBIN0000001","Main bank",true);
        var withdrawal=new WithdrawalDtos.Withdrawal(id,"alice","Alice","alice@example.invalid","123",10025,bank,"SUCCESSFUL",null,null,null,"REF-123",java.time.Instant.now(),null,java.time.Instant.now(),null,java.time.Instant.now());
        when(withdrawals.adminDetail(id,"reviewer")).thenReturn(new WithdrawalDtos.AdminWithdrawal(withdrawal,"12345678901"));
        mvc.perform(get("/api/admin/withdrawals/"+ID).with(user("reviewer").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ID))
            .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
            .andExpect(jsonPath("$.accountNumber").value("12345678901"))
            .andExpect(header().string("Cache-Control",containsString("no-store")));
        when(withdrawals.detail("alice",id)).thenReturn(withdrawal);
        mvc.perform(get("/api/withdrawals/"+ID).cookie(session()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accountNumber").doesNotExist())
            .andExpect(content().string(not(containsString("12345678901"))));
    }

    @Test void liveStreamRequiresIdentityAndAsyncCompletionRetainsAuthentication() throws Exception {
        var emitter=new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60000L);
        when(withdrawals.subscribe("alice")).thenReturn(emitter);
        var result=mvc.perform(get("/api/withdrawals/events").cookie(session()))
            .andExpect(request().asyncStarted()).andReturn();
        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("withdrawal-change").data("refresh"));
        emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        verify(withdrawals).subscribe("alice");
    }
}
