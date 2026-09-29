package com.iare.hackathon.auth;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.user.UserProfileService;
import com.iare.hackathon.user.ProfileStorageUnavailableException;
import java.util.Optional;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.firebase.enabled=false", "app.firebase.secure-cookie=true", "app.supabase.enabled=false"})
@AutoConfigureMockMvc
class AuthenticationFlowTests {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuthService firebase;
    @MockitoBean UserProfileService profiles;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder csrfPost(String path) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.token");
        String header = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.headerName");
        return post(path).cookie(response.getCookie("XSRF-TOKEN")).header(header, token);
    }

    @Test
    void publicPagesRenderWithoutFirebaseCredentials() throws Exception {
        for (String path : new String[]{"/", "/register", "/login", "/forgot-password"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(view().name("auth"))
                    .andExpect(content().string(containsString("Continue with Google")));
        }
    }

    @Test
    void protectedPagesRedirectAndApisReturnUnauthorized() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/future-feature")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void configurationExposesOnlyPublicValues() throws Exception {
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.firebase.apiKey").exists())
                .andExpect(jsonPath("$.firebase.serviceAccountPath").doesNotExist())
                .andExpect(jsonPath("$.supabase").doesNotExist())
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("private_key"))));
    }

    @Test
    void sessionAndLogoutRejectRequestsWithoutCsrf() throws Exception {
        mvc.perform(post("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"fake\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        verifyNoInteractions(firebase);
    }

    @Test
    void sessionRejectsBlankToken() throws Exception {
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(firebase);
    }

    @Test
    void validIdTokenCreatesPersistentSecureHttpOnlyCookie() throws Exception {
        var user = new AuthUser("uid-123", "Alex", "alex@example.com", null, true, 1000, 2000, "google.com");
        when(firebase.createSession("valid-token")).thenReturn(new FirebaseAuthService.VerifiedLogin("signed-session", user));
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"valid-token\"}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(SessionCookies.NAME, "signed-session"))
                .andExpect(cookie().httpOnly(SessionCookies.NAME, true))
                .andExpect(cookie().secure(SessionCookies.NAME, true))
                .andExpect(cookie().maxAge(SessionCookies.NAME, 432000))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
        var order = inOrder(firebase, profiles);
        order.verify(firebase).createSession("valid-token");
        order.verify(profiles).synchronize(user);
    }

    @Test
    void invalidFirebaseTokenDoesNotCreateSession() throws Exception {
        when(firebase.createSession("invalid")).thenThrow(mock(FirebaseAuthException.class));
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"invalid\"}"))
                .andExpect(status().isUnauthorized()).andExpect(cookie().doesNotExist(SessionCookies.NAME));
    }

    @Test
    void certificateFailureIsNotReportedAsAnExpiredLogin() throws Exception {
        var error = mock(FirebaseAuthException.class);
        when(error.getAuthErrorCode()).thenReturn(com.google.firebase.auth.AuthErrorCode.CERTIFICATE_FETCH_FAILED);
        when(firebase.createSession("valid-token")).thenThrow(error);
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"valid-token\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_FETCH_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("verification certificates")))
                .andExpect(cookie().doesNotExist(SessionCookies.NAME));
    }

    @Test
    void recentAuthenticationFailurePreservesTheActualReason() throws Exception {
        when(firebase.createSession("old-token")).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "This sign-in is more than five minutes old."));
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"old-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This sign-in is more than five minutes old."));
    }

    @Test
    void validCookieSurvivesIndependentRequestsAndRedirectsAwayFromLogin() throws Exception {
        var token = mock(FirebaseToken.class);
        when(firebase.verifySession("valid-session")).thenReturn(token);
        for (int refresh = 0; refresh < 2; refresh++) {
            mvc.perform(get("/dashboard").cookie(new Cookie(SessionCookies.NAME, "valid-session")))
                    .andExpect(status().isOk()).andExpect(view().name("dashboard"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }
        mvc.perform(get("/login").cookie(new Cookie(SessionCookies.NAME, "valid-session")))
                .andExpect(redirectedUrl("/dashboard"));
        verify(firebase, times(3)).verifySession("valid-session");
    }

    @Test
    void profileUsesVerifiedIdentityRatherThanClientProvidedUid() throws Exception {
        var token = mock(FirebaseToken.class);
        when(firebase.verifySession("valid-session")).thenReturn(token);
        when(token.getUid()).thenReturn("uid-123");
        when(profiles.findByUid("uid-123")).thenReturn(Optional.of(new AuthUser("uid-123", "Alex", "alex@example.com",
                null, true, 1000, 2000, "google.com")));
        mvc.perform(get("/api/auth/me?uid=someone-else").cookie(new Cookie(SessionCookies.NAME, "valid-session")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uid").value("uid-123"))
                .andExpect(jsonPath("$.provider").value("google.com"));
        verify(firebase, never()).profile(any());
        verify(profiles, never()).findByUid("someone-else");
    }

    @Test
    void databaseFailurePreventsIssuingSessionCookie() throws Exception {
        var user = new AuthUser("uid-123", "Alex", "alex@example.com", null, true, 1000, 2000, "google.com");
        when(firebase.createSession("valid-token")).thenReturn(new FirebaseAuthService.VerifiedLogin("signed-session", user));
        when(profiles.synchronize(user)).thenThrow(new ProfileStorageUnavailableException());
        mvc.perform(csrfPost("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"valid-token\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(cookie().doesNotExist(SessionCookies.NAME))
                .andExpect(jsonPath("$.message").value(containsString("temporarily unavailable")));
    }

    @Test
    void preExistingFirebaseSessionGetsProfileMigratedOnFirstRead() throws Exception {
        var token = mock(FirebaseToken.class);
        var user = new AuthUser("legacy-uid", "Alex", "alex@example.com", null, true, 1000, 2000, "google.com");
        when(token.getUid()).thenReturn("legacy-uid");
        when(firebase.verifySession("legacy-session")).thenReturn(token);
        when(profiles.findByUid("legacy-uid")).thenReturn(Optional.empty());
        when(firebase.profile(token)).thenReturn(user);
        when(profiles.synchronize(user)).thenReturn(user);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, "legacy-session")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uid").value("legacy-uid"));
        verify(profiles).synchronize(user);
    }

    @Test
    void databaseReadFailureReturns503WithoutLoggingUserOut() throws Exception {
        var token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-123");
        when(firebase.verifySession("valid-session")).thenReturn(token);
        when(profiles.findByUid("uid-123")).thenThrow(new ProfileStorageUnavailableException());
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, "valid-session")))
                .andExpect(status().isServiceUnavailable()).andExpect(cookie().doesNotExist(SessionCookies.NAME));
    }

    @Test
    void revokedOrExpiredCookieIsClearedAndCannotAccessDashboard() throws Exception {
        when(firebase.verifySession("revoked")).thenThrow(mock(FirebaseAuthException.class));
        mvc.perform(get("/dashboard").cookie(new Cookie(SessionCookies.NAME, "revoked")))
                .andExpect(redirectedUrl("/login")).andExpect(cookie().maxAge(SessionCookies.NAME, 0));
    }

    @Test
    void logoutClearsCookieEvenIfSessionAlreadyExpired() throws Exception {
        mvc.perform(csrfPost("/api/auth/logout").cookie(new Cookie(SessionCookies.NAME, "expired")))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge(SessionCookies.NAME, 0))
                .andExpect(cookie().value(SessionCookies.NAME, ""));
        verifyNoInteractions(firebase);
    }

    @Test
    void newPagesRequireLoginAndRenderForVerifiedUsers() throws Exception {
        var token=mock(FirebaseToken.class);
        when(firebase.verifySession("valid-session")).thenReturn(token);
        for(String path:new String[]{"/news","/price","/profile","/features/recharge","/features/withdrawal","/features/invitation","/features/my-product","/features/renib-bonus","/features/customer-service","/features/my-team","/features/monthly-salary","/features/calculator","/machines/00000000-0000-4000-8000-000000000001"}){
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
            mvc.perform(get(path).cookie(new Cookie(SessionCookies.NAME,"valid-session")))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("aria-label=\"Main navigation\"")));
        }
        mvc.perform(get("/features/unknown").cookie(new Cookie(SessionCookies.NAME,"valid-session"))).andExpect(status().isNotFound());
    }

    @Test
    void csrfEndpointIssuesTokenAndHttpOnlyCookie() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", true));
    }

    @Test void informationPagesArePublicAndActivityRemainsProtected() throws Exception {
        for(String slug:new String[]{"about","contact","faq","terms","privacy","refund-cancellation","withdrawal","risk-disclaimer"})
            mvc.perform(get("/information/"+slug)).andExpect(status().isOk()).andExpect(view().name("information"))
                .andExpect(content().string(containsString("Customer Support")));
        mvc.perform(get("/information/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/activity")).andExpect(status().isUnauthorized());
    }
}
