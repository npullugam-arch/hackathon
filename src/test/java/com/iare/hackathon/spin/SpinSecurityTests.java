package com.iare.hackathon.spin;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.auth.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
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
class SpinSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuthService firebase;
    @MockitoBean SpinService spins;
    private static final String ID="00000000-0000-4000-8000-000000000001";
    Cookie session() throws Exception {
        var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn("alice");when(firebase.verifySession("spin-session")).thenReturn(token);
        return new Cookie(SessionCookies.NAME,"spin-session");
    }
    @Test void readsAndSpinsRequireLogin() throws Exception {
        mvc.perform(get("/api/spin")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/spin").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(get("/features/renib-bonus")).andExpect(status().is3xxRedirection());verifyNoInteractions(spins);
    }
    @Test void spinRequiresCsrf() throws Exception {
        mvc.perform(post("/api/spin").cookie(session()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());verifyNoInteractions(spins);
    }
    @Test void missingAndInvalidRequestFieldsAreRejected() throws Exception {
        for(String body:List.of("{}","{\"requestId\":\"invalid\",\"spinDay\":\"2026-09-27\"}","{\"requestId\":\""+ID+"\",\"spinDay\":\"not-a-date\"}"))
            mvc.perform(post("/api/spin").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(spins);
    }
    @Test void sessionIdentityAndServerRewardCannotBeOverridden() throws Exception {
        mvc.perform(post("/api/spin").cookie(session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\""+ID+"\",\"spinDay\":\"2026-09-27\",\"userId\":\"bob\",\"amountPaise\":10000000,\"reward\":50000}"))
            .andExpect(status().isOk());
        verify(spins).spin("alice",new SpinDtos.Request(UUID.fromString(ID),LocalDate.of(2026,9,27)));
    }
    @Test void statusIsOwnedAndNeverCached() throws Exception {
        when(spins.status("alice")).thenReturn(new SpinDtos.State("alice",Instant.now(),LocalDate.of(2026,9,27),Instant.now().plusSeconds(3600),true,0,List.of(),null,List.of()));
        mvc.perform(get("/api/spin?userId=bob").cookie(session())).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("alice")).andExpect(header().string("Cache-Control",containsString("no-store")));
        verify(spins).status("alice");
    }
    @Test void existingBonusRouteRendersNewWheel() throws Exception {
        mvc.perform(get("/features/renib-bonus").cookie(session())).andExpect(status().isOk()).andExpect(view().name("spin"))
            .andExpect(content().string(containsString("id=\"spin-wheel\""))).andExpect(content().string(not(containsString("50,000"))));
    }
}
