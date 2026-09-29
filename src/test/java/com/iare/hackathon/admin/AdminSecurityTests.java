package com.iare.hackathon.admin;

import com.iare.hackathon.auth.*;
import com.iare.hackathon.catalog.CatalogService;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.firebase.enabled=false", "app.supabase.enabled=false",
        "app.admin.email=admin@example.test", "app.admin.password=test-admin-password"})
@AutoConfigureMockMvc
class AdminSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuthService firebase;
    @MockitoBean CatalogService catalog;
    @MockitoBean AdminRechargeService recharges;
    @MockitoBean com.iare.hackathon.phototask.PhotoTaskService photos;
    @MockitoBean com.iare.hackathon.phototask.MachineRewardService machineRewards;
    record Token(MockHttpSession session, String header, String value) { }
    Token csrf(MockHttpSession session) throws Exception {
        var request=get("/api/admin/csrf"); if(session!=null)request.session(session);
        var result=mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Token((MockHttpSession)result.getRequest().getSession(),
                com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.headerName"),
                com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.token"));
    }
    MockHttpSession login() throws Exception {
        var token=csrf(null);
        return (MockHttpSession)mvc.perform(post("/api/admin/login").session(token.session())
                .header(token.header(),token.value()).param("email","admin@example.test").param("password","test-admin-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession();
    }
    @Test void adminLandingIsPublicButDashboardAndApisAreProtected() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(view().name("admin-login"));
        mvc.perform(get("/admin/dashboard")).andExpect(redirectedUrl("/admin"));
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/login").param("email","admin@example.test").param("password","test-admin-password"))
                .andExpect(status().isForbidden());
    }
    @Test void wrongCredentialsNeverGrantAdminAccess() throws Exception {
        var token=csrf(null);
        mvc.perform(post("/api/admin/login").session(token.session()).header(token.header(),token.value())
                .param("email","admin@example.test").param("password","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dashboard").session(token.session())).andExpect(redirectedUrl("/admin"));
    }
    @Test void firebaseUserCannotBecomeAdminEvenWithMatchingEmail() throws Exception {
        var token=mock(FirebaseToken.class);when(token.getEmail()).thenReturn("admin@example.test");
        when(firebase.verifySession("user-session")).thenReturn(token);when(token.getUid()).thenReturn("alice");
        mvc.perform(get("/api/admin/products").cookie(new Cookie(SessionCookies.NAME,"user-session"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(firebase);
    }
    @Test void adminLoginPersistsAndLogoutInvalidatesSession() throws Exception {
        var session=login();
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin").session(session)).andExpect(redirectedUrl("/admin/dashboard"));
        mvc.perform(get("/api/admin/products").session(session)).andExpect(status().isOk());
        // A backend admin session does not impersonate a Firebase user.
        mvc.perform(get("/api/products").session(session)).andExpect(status().isUnauthorized());
        var token=csrf(session);
        mvc.perform(post("/api/admin/logout").session(session).header(token.header(),token.value())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
    }
    @Test void adminMutationsRequireCsrfAndValidatePayloads() throws Exception {
        var session=login();
        mvc.perform(post("/api/admin/products").session(session).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        var token=csrf(session);
        mvc.perform(post("/api/admin/products").session(session).header(token.header(),token.value()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }
    @Test void userListsOnlyActiveProductsAndAdsWithoutExposingCookie() throws Exception {
        var token=mock(FirebaseToken.class);when(firebase.verifySession("user-session")).thenReturn(token);when(token.getUid()).thenReturn("alice");
        when(catalog.products(true)).thenReturn(java.util.List.of());
        var productId=java.util.UUID.randomUUID();var purchaseId=java.util.UUID.randomUUID();
        when(catalog.purchasesForUser("alice")).thenReturn(java.util.Map.of(productId,purchaseId));
        when(catalog.advertisements(true)).thenReturn(java.util.List.of());
        mvc.perform(get("/api/products?userId=bob").cookie(new Cookie(SessionCookies.NAME,"user-session"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.purchases."+productId).value(purchaseId.toString()));
        var result=mvc.perform(get("/api/advertisements/current").cookie(new Cookie(SessionCookies.NAME,"user-session"))).andExpect(status().isOk()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("user-session"));
        verify(catalog).products(true);verify(catalog).purchasesForUser("alice");verify(catalog,never()).purchasesForUser("bob");verify(catalog).advertisements(true);
    }

    @Test void machineAdminWritesRequireCsrfAndValidatedFields() throws Exception {
        var session=login();
        mvc.perform(get("/api/admin/machines").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/machines").session(session).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        var token=csrf(session);
        mvc.perform(post("/api/admin/machines").session(session).header(token.header(),token.value()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verify(catalog).machines(false);
        verifyNoMoreInteractions(catalog);
    }
    @Test void machinesRequireAuthenticationAndNeverGrantNormalUsersAdminAccess() throws Exception {
        mvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/machines")).andExpect(status().isUnauthorized());
        var token=mock(FirebaseToken.class);when(firebase.verifySession("machine-user")).thenReturn(token);
        when(catalog.machines(true)).thenReturn(java.util.List.of());
        mvc.perform(get("/api/machines").cookie(new Cookie(SessionCookies.NAME,"machine-user"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/admin/machines").cookie(new Cookie(SessionCookies.NAME,"machine-user"))).andExpect(status().isUnauthorized());
        verify(catalog).machines(true);
    }
    @Test void rechargeTrackingRejectsAnonymousFirebaseAndNonAdminUsers() throws Exception {
        for (String path : new String[]{"/api/admin/recharges", "/api/admin/recharges/00000000-0000-4000-8000-000000000001"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).cookie(new Cookie(SessionCookies.NAME, "user-session"))).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("regular").roles("USER")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(recharges);
    }
    @Test void adminsCanSeeTrackingAndOnlySafeDtosAreReturned() throws Exception {
        var session = login();
        var summary = new AdminRechargeDtos.Summary(0, 0, 0, 0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "INR");
        when(recharges.list(any())).thenReturn(new AdminRechargeDtos.Page(java.util.List.of(), summary, 0, 0, 25, 0, java.time.Instant.now()));
        mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Recharge Transactions")));
        mvc.perform(get("/api/admin/recharges").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalAttempts").value(0)).andExpect(jsonPath("$.items").isArray())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        var captor = org.mockito.ArgumentCaptor.forClass(AdminRechargeDtos.Filter.class); verify(recharges).list(captor.capture());
        assertEquals(0, captor.getValue().page()); assertEquals(25, captor.getValue().size());
        mvc.perform(get("/api/admin/recharges/00000000-0000-4000-8000-000000000001").session(session)).andExpect(status().isOk());
        verify(recharges).detail(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"));
    }
    @Test void adminRechargeFilterValidationRejectsInvalidRequests() throws Exception {
        var session = login();
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101", "?status=FORGED", "?from=not-a-date", "?name=" + "a".repeat(161)})
            mvc.perform(get("/api/admin/recharges" + query).session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/recharges/not-a-uuid").session(session)).andExpect(status().isBadRequest());
        verifyNoInteractions(recharges);
    }

    @Test void photoTasksSeparateUserAndAdminAuthorityAndRequireCsrf() throws Exception {
        var id=java.util.UUID.randomUUID();var user=mock(FirebaseToken.class);when(user.getUid()).thenReturn("alice");when(firebase.verifySession("photo-session")).thenReturn(user);
        var cookie=new Cookie(SessionCookies.NAME,"photo-session");
        mvc.perform(get("/api/photo-tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/photo-tasks").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/photo-tasks/"+id+"/image").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/tasks/take-photo")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/tasks/take-photo").cookie(cookie)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Upload a photo while using the Launchpad")));
        when(photos.image(id,"alice")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Unavailable"));
        mvc.perform(get("/api/photo-tasks/"+id+"/image").cookie(cookie)).andExpect(status().isNotFound());verify(photos).image(id,"alice");
        var session=login();mvc.perform(put("/api/admin/photo-tasks/"+id).session(session).contentType("application/json").content("{\"status\":\"COMPLETED\"}")).andExpect(status().isForbidden());
        var token=csrf(session);mvc.perform(put("/api/admin/photo-tasks/"+id).session(session).header(token.header(),token.value()).contentType("application/json").content("{\"status\":\"COMPLETED\"}")).andExpect(status().isOk());
        verify(photos).review(eq(id),any(),eq("admin@example.test"));
        mvc.perform(put("/api/admin/photo-tasks/"+id).session(session).header(token.header(),token.value()).contentType("application/json").content("{\"status\":\"PENDING\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/photo-tasks").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void multipartPhotoSubmissionUsesVerifiedUidNotSubmittedOwner() throws Exception {
        var user=mock(FirebaseToken.class);when(user.getUid()).thenReturn("alice");when(firebase.verifySession("photo-upload-session")).thenReturn(user);
        var cookie=new Cookie(SessionCookies.NAME,"photo-upload-session");var requestId=java.util.UUID.randomUUID();
        var csrfResult=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse();
        String header=com.jayway.jsonpath.JsonPath.read(csrfResult.getContentAsString(),"$.headerName"),token=com.jayway.jsonpath.JsonPath.read(csrfResult.getContentAsString(),"$.token");
        var file=new org.springframework.mock.web.MockMultipartFile("photo","image.png","image/png",new byte[]{1,2});
        mvc.perform(multipart("/api/photo-tasks").file(file).cookie(cookie).param("requestId",requestId.toString())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/photo-tasks").file(file).cookie(cookie,csrfResult.getCookie("XSRF-TOKEN")).header(header,token).param("requestId",requestId.toString()).param("userId","bob")).andExpect(status().isOk());
        verify(photos).submit(eq("alice"),eq(requestId),any());
    }

    @Test void machineClaimsRequireUserSessionCsrfAndUseOnlyVerifiedUid() throws Exception {
        var user=mock(FirebaseToken.class);when(user.getUid()).thenReturn("alice");when(firebase.verifySession("claim-session")).thenReturn(user);var cookie=new Cookie(SessionCookies.NAME,"claim-session");var id=java.util.UUID.randomUUID();
        mvc.perform(post("/api/machine-rewards/"+id+"/claim").cookie(cookie)).andExpect(status().isForbidden());
        var csrfResponse=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();String header=com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(),"$.headerName"),token=com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(),"$.token");
        mvc.perform(post("/api/machine-rewards/"+id+"/claim").cookie(cookie,csrfResponse.getCookie("XSRF-TOKEN")).header(header,token).contentType("application/json").content("{\"userId\":\"bob\",\"amountPaise\":999999}")) .andExpect(status().isOk());verify(machineRewards).claim("alice",id);
        when(machineRewards.claim("alice",id)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"This reward is not available."));
        mvc.perform(post("/api/machine-rewards/"+id+"/claim").cookie(cookie,csrfResponse.getCookie("XSRF-TOKEN")).header(header,token)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("This reward is not available."));
    }
}
