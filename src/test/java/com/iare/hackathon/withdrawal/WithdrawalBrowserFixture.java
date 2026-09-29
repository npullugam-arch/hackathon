package com.iare.hackathon.withdrawal;

import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.HackathonApplication;
import com.iare.hackathon.auth.*;
import com.iare.hackathon.catalog.CatalogRepository;
import com.iare.hackathon.user.*;
import com.iare.hackathon.wallet.WalletRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.time.*;
import com.iare.hackathon.commerce.*;
import com.iare.hackathon.catalog.ProductInput;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;

/** Real HTTP, templates, withdrawal services and PostgreSQL. Only Firebase identity is simulated. */
public class WithdrawalBrowserFixture {
    static class BrowserClock extends Clock { volatile Instant now=Instant.parse("2026-09-24T06:00:00Z");private final long started=System.nanoTime();public Instant instant(){return now.plusNanos(System.nanoTime()-started);}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;} }
    @TestConfiguration
    static class Fixture {
        @Bean(destroyMethod="close") EmbeddedPostgres browserPostgres() throws Exception { return EmbeddedPostgres.builder().setPort(0).start(); }
        @Bean JdbcTemplate browserJdbc(EmbeddedPostgres postgres) {
            var source=postgres.getPostgresDatabase();Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private")
                    .createSchemas(true).locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(source);
            new JdbcUserProfileRepository(jdbc).upsert(new AuthUser("withdraw-browser-user","Test User","withdraw@example.invalid",null,true,1000,2000,"google.com"));
            new JdbcUserProfileRepository(jdbc).upsert(new AuthUser("invite-browser-inviter","John Doe","inviter@example.invalid","https://example.invalid/inviter.png",true,1000,2000,"google.com"));
            new JdbcUserProfileRepository(jdbc).upsert(new AuthUser("invite-browser-invitee","Jane Doe","invitee@example.invalid",null,true,1000,2000,"google.com"));
            // A historical captured recharge in the disposable fixture, not a provider API call.
            jdbc.update("INSERT INTO app_private.recharge_orders(id,user_id,razorpay_order_id,amount_paise,currency,status) VALUES (?,'withdraw-browser-user','order_browser',100000,'INR','CREDITED')",UUID.randomUUID());
            jdbc.update("INSERT INTO app_private.wallet_transactions(id,user_id,razorpay_order_id,razorpay_payment_id,amount_paise,currency,payment_status,transaction_type,direction) VALUES (?,'withdraw-browser-user','order_browser','pay_browser',100000,'INR','CAPTURED','RECHARGE','CREDIT')",UUID.randomUUID());
            jdbc.update("UPDATE public.users SET wallet_balance_paise=100000 WHERE firebase_uid='withdraw-browser-user'");
            return jdbc;
        }
        @Bean @Primary BrowserClock browserClock(){return new BrowserClock();}
        @Bean CommerceRepository browserCommerce(JdbcTemplate jdbc){return new CommerceRepository(jdbc);}
        @Bean WithdrawalRepository browserWithdrawals(JdbcTemplate jdbc){return new WithdrawalRepository(jdbc);}
        @Bean WalletRepository browserWallet(JdbcTemplate jdbc){return new WalletRepository(jdbc);}
        @Bean UserProfileRepository browserProfiles(JdbcTemplate jdbc){return new JdbcUserProfileRepository(jdbc);}
        @Bean CatalogRepository browserCatalog(JdbcTemplate jdbc){return new CatalogRepository(jdbc);}
        @Bean @Primary FirebaseAuthService browserFirebase() throws Exception {
            var firebase=mock(FirebaseAuthService.class);var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn("withdraw-browser-user");
            when(firebase.verifySession("withdrawal-browser-session")).thenReturn(token);
            for(String role:List.of("inviter","invitee")){var identity=mock(FirebaseToken.class);when(identity.getUid()).thenReturn("invite-browser-"+role);when(firebase.verifySession("invitation-browser-"+role)).thenReturn(identity);}
            return firebase;
        }
    }
    public static void main(String[] args) throws Exception {
        var app=new SpringApplication(HackathonApplication.class,Fixture.class);
        var context=app.run("--server.address=127.0.0.1","--server.port=8097","--app.firebase.enabled=false","--app.supabase.enabled=false",
                "--app.firebase.secure-cookie=false","--razorpay.key.id=","--razorpay.key.secret=","--app.admin.email=admin@example.invalid",
                "--app.admin.password=browser-test-password","--debug=false","--logging.level.root=INFO",
                "--app.security.key-directory="+java.nio.file.Files.createTempDirectory("withdraw-browser-keys-"),"--logging.level.org.springframework.jdbc=INFO","--logging.level.org.springframework.web=INFO");
        if(Arrays.asList(args).contains("purchase-flow")) {
            var jdbc=context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE public.users SET wallet_balance_paise=100000 WHERE firebase_uid='invite-browser-invitee'");
            context.getBean(CatalogRepository.class).saveProduct(UUID.randomUUID(),new ProductInput("Single purchase product","https://example.invalid/product.png",new BigDecimal("250"),new BigDecimal("250"),40,new BigDecimal("20"),"Disposable purchase test",true),true);
            System.out.println("WITHDRAWAL_BROWSER_READY");
            try{new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();}finally{context.close();}
            return;
        }
        var clock=context.getBean(BrowserClock.class);var product=UUID.randomUUID();
        context.getBean(CatalogRepository.class).saveProduct(product,new ProductInput("Compact claim product","https://example.invalid/product.png",new BigDecimal("250"),new BigDecimal("250"),1,new BigDecimal("2250"),"Browser test only",true),true);
        var purchase=context.getBean(CommerceService.class).buy("withdraw-browser-user",new CommerceDtos.Buy(product)).purchase();
        // The test clock ticks so browser checks exercise live cycle updates.
        System.out.println("WITHDRAWAL_BROWSER_READY");
        try{new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();}finally{context.close();}
    }
}
