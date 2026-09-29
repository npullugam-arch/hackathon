package com.iare.hackathon.user;

import com.iare.hackathon.auth.AuthUser;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Runs production migrations and repository SQL on a real, disposable local PostgreSQL server. */
class JdbcUserProfileRepositoryTests {
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static JdbcUserProfileRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        var dataSource = postgres.getPostgresDatabase();
        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("app_private").defaultSchema("app_private").createSchemas(true).load();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("app_private").defaultSchema("app_private").target("1").load().migrate();
        var legacyJdbc = new JdbcTemplate(dataSource);
        legacyJdbc.update("INSERT INTO app_private.user_profiles (firebase_uid, display_name, email_verified, auth_provider, firebase_created_at, last_sign_in_at) VALUES ('legacy-user', 'Existing account', true, 'google.com', 1000, 2000)");
        flyway.migrate();
        assertEquals("Existing account", legacyJdbc.queryForObject("SELECT name FROM public.users WHERE firebase_uid = 'legacy-user'", String.class));
        assertEquals(2000, legacyJdbc.queryForObject("SELECT last_login_at FROM public.users WHERE firebase_uid = 'legacy-user'", java.sql.Timestamp.class).getTime());
        assertEquals(0, flyway.migrate().migrationsExecuted, "Repeated startup must not recreate tables");
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcUserProfileRepository(jdbc);
    }
    @AfterAll
    static void stopDatabase() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach
    void clearProfiles() { jdbc.update("DELETE FROM public.users"); }

    private AuthUser user(String uid, String email, String name, long loginTime) {
        return new AuthUser(uid, name, email, null, true, 1000, loginTime, "google.com");
    }

    @Test
    void loginUpsertsProfileAndPreservesCreationTimeAcrossEmailChanges() {
        var first = repository.upsert(user("uid-1", "first@example.com", "First name", 2000));
        var updated = repository.upsert(new AuthUser("uid-1", "Updated name", "updated@example.com",
                "https://example.com/new-photo.jpg", true, 1000, 3000, "google.com"));
        assertEquals("https://example.com/new-photo.jpg", updated.photoUrl());
        assertEquals(first.createdAt(), updated.createdAt());
        assertEquals("Updated name", repository.findByFirebaseUid("uid-1").orElseThrow().displayName());
        assertEquals("updated@example.com", updated.email());
        assertEquals(3000, updated.lastSignInAt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM public.users", Integer.class));
        repository.upsert(user("uid-1", "updated@example.com", "Updated name", 2000));
        assertEquals(3000, repository.findByFirebaseUid("uid-1").orElseThrow().lastSignInAt());
    }

    @Test
    void concurrentFirstLoginsProduceExactlyOneProfile() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Callable<UserProfile>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> repository.upsert(user("same-uid", "same@example.com", "Name", 2000)));
            for (var result : executor.invokeAll(tasks)) assertEquals("same-uid", result.get().firebaseUid());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM public.users", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void mutableEmailNeverMergesDifferentIdentitiesAndSqlValuesAreBound() {
        repository.upsert(user("uid-1", "same@example.com", "O'Brien'; DROP TABLE user_profiles; --", 2000));
        repository.upsert(user("uid-2", "same@example.com", null, 2000));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM public.users", Integer.class));
        assertEquals("O'Brien'; DROP TABLE user_profiles; --", repository.findByFirebaseUid("uid-1").orElseThrow().displayName());
        assertTrue(repository.findByFirebaseUid("' OR '1'='1").isEmpty());
        assertNull(repository.findByFirebaseUid("uid-2").orElseThrow().displayName());
    }

    @Test
    void publicUsersTableHasRowLevelSecurityEnabled() {
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = 'public.users'::regclass", Boolean.class));
    }

    @Test
    void loginEndpointCommitsUserBeforeIssuingCookieAndUpdatesOnNextLogin() throws Exception {
        var firebase = org.mockito.Mockito.mock(com.iare.hackathon.auth.FirebaseAuthService.class);
        var properties = org.mockito.Mockito.mock(com.iare.hackathon.auth.FirebaseProperties.class);
        org.mockito.Mockito.when(properties.sessionDuration()).thenReturn(java.time.Duration.ofDays(5));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("users", repository);
        var service = new UserProfileService(beans.getBeanProvider(UserProfileRepository.class));
        var controller = new com.iare.hackathon.auth.AuthController(properties, firebase,
                new com.iare.hackathon.auth.SessionCookies(properties), service);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (int login = 1; login <= 2; login++) {
            var identity = user("google-login-user", "test@example.com", "Name " + login, login * 1000);
            org.mockito.Mockito.when(firebase.createSession("verified-token"))
                    .thenReturn(new com.iare.hackathon.auth.FirebaseAuthService.VerifiedLogin("signed-session", identity));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/session")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"idToken\":\"verified-token\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                            .value(com.iare.hackathon.auth.SessionCookies.NAME, "signed-session"));
            assertEquals("Name " + login, repository.findByFirebaseUid("google-login-user").orElseThrow().displayName());
            assertEquals(login * 1000, repository.findByFirebaseUid("google-login-user").orElseThrow().lastSignInAt());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM public.users", Integer.class));
        }
    }
}
