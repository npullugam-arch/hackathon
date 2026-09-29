package com.iare.hackathon.wallet;

import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.HackathonApplication;
import com.iare.hackathon.auth.*;
import com.iare.hackathon.catalog.CatalogRepository;
import com.iare.hackathon.user.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local browser fixture: real application and disposable PostgreSQL; only identity/provider are simulated. */
public class WalletBrowserFixture {
    @TestConfiguration
    static class Fixture {
        @Bean(destroyMethod = "close") EmbeddedPostgres browserPostgres() throws Exception { return EmbeddedPostgres.builder().setPort(0).start(); }
        @Bean JdbcTemplate browserJdbc(EmbeddedPostgres postgres) {
            var source = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").createSchemas(true)
                    .locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            new JdbcUserProfileRepository(jdbc).upsert(new AuthUser("browser-user", "Test User", "test@example.invalid", null, true, 1000, 2000, "google.com"));
            return jdbc;
        }
        @Bean WalletRepository browserWallet(JdbcTemplate jdbc) { return new WalletRepository(jdbc); }
        @Bean UserProfileRepository browserProfiles(JdbcTemplate jdbc) { return new JdbcUserProfileRepository(jdbc); }
        @Bean CatalogRepository browserCatalog(JdbcTemplate jdbc) { return new CatalogRepository(jdbc); }
        @Bean @Primary FirebaseAuthService browserFirebase() throws Exception {
            var firebase = mock(FirebaseAuthService.class); var token = mock(FirebaseToken.class);
            when(token.getUid()).thenReturn("browser-user");
            when(firebase.verifySession("wallet-browser-session")).thenReturn(token);
            return firebase;
        }
        @Bean @Primary RazorpayGateway browserGateway() {
            var gateway = mock(RazorpayGateway.class);
            var amounts = new ConcurrentHashMap<String, Long>(); var counter = new AtomicInteger();
            var attempts = new ConcurrentHashMap<String, AtomicInteger>();
            when(gateway.createOrder(anyLong(), anyString())).thenAnswer(call -> {
                String id = "order_Test" + counter.incrementAndGet(); long amount = call.getArgument(0);
                amounts.put(id, amount); return new RazorpayGateway.RemoteOrder(id, amount, "INR");
            });
            when(gateway.fetchPayment(anyString())).thenAnswer(call -> {
                String id = call.getArgument(0); String order = id.replace("pay_", "order_").replace("Pending", "");
                boolean captured = !id.contains("Pending") || attempts.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet() > 1;
                return new RazorpayGateway.Payment(id, order, amounts.getOrDefault(order, 0L), "INR", captured ? "captured" : "authorized", captured, 0);
            });
            return gateway;
        }
    }
    public static void main(String[] args) throws Exception {
        var context = new SpringApplication(HackathonApplication.class, Fixture.class).run(
                "--server.port=8096", "--server.address=127.0.0.1", "--app.firebase.enabled=false", "--app.supabase.enabled=false",
                "--app.firebase.secure-cookie=false", "--razorpay.key.id=rzp_test_fixture", "--razorpay.key.secret=fixture-secret",
                "--debug=false");
        // Let the runner close the application and its disposable database gracefully on Windows.
        try { new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine(); }
        finally { context.close(); }
    }
}
