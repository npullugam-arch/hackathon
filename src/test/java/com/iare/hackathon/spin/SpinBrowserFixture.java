package com.iare.hackathon.spin;

import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.HackathonApplication;
import com.iare.hackathon.auth.*;
import com.iare.hackathon.catalog.CatalogRepository;
import com.iare.hackathon.user.*;
import com.iare.hackathon.withdrawal.WithdrawalRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;

/** Disposable HTTP/database fixture. Test clock controls exist only on this test classpath. */
public class SpinBrowserFixture {
    static class BrowserClock extends Clock {
        volatile Instant time=Instant.parse("2026-09-27T18:00:00Z");
        volatile long start=System.nanoTime();
        public Instant instant(){return time.plusNanos(System.nanoTime()-start);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        synchronized void beforeMidnight(){time=Instant.parse("2026-09-27T18:29:58Z");start=System.nanoTime();}
    }
    @RestController
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.spin.browser-fixture",havingValue="true")
    static class ClockControl {
        private final BrowserClock clock;
        ClockControl(BrowserClock clock){this.clock=clock;}
        @PostMapping("/api/test/spin-before-midnight") Map<String,Boolean> advance(){clock.beforeMidnight();return Map.of("ok",true);}
    }
    @TestConfiguration
    static class Fixture {
        @Bean(destroyMethod="close") EmbeddedPostgres spinPostgres() throws Exception {return EmbeddedPostgres.builder().setPort(0).start();}
        @Bean JdbcTemplate spinJdbc(EmbeddedPostgres postgres){
            var source=postgres.getPostgresDatabase();Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").createSchemas(true).locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(source);
            for(String uid:List.of("spin-alice","spin-bob")) new JdbcUserProfileRepository(jdbc).upsert(new AuthUser(uid,uid,uid+"@example.invalid",null,true,1000,2000,"google.com"));
            return jdbc;
        }
        @Bean SpinRepository spinRepository(JdbcTemplate jdbc){return new SpinRepository(jdbc);}
        @Bean WithdrawalRepository spinWinningRepository(JdbcTemplate jdbc){return new WithdrawalRepository(jdbc);}
        @Bean CatalogRepository spinCatalog(JdbcTemplate jdbc){return new CatalogRepository(jdbc);}
        @Bean UserProfileRepository spinProfiles(JdbcTemplate jdbc){return new JdbcUserProfileRepository(jdbc);}
        @Bean @Primary BrowserClock spinClock(){return new BrowserClock();}
        @Bean @Primary FirebaseAuthService spinFirebase() throws Exception {
            var firebase=mock(FirebaseAuthService.class);
            for(String uid:List.of("spin-alice","spin-bob")){var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn(uid);when(firebase.verifySession(uid+"-session")).thenReturn(token);}
            return firebase;
        }
    }
    public static void main(String[] args) throws Exception {
        var app=new SpringApplication(HackathonApplication.class,Fixture.class);
        var context=app.run("--server.address=127.0.0.1","--server.port=8098","--app.firebase.enabled=false","--app.supabase.enabled=false","--app.spin.browser-fixture=true",
            "--app.firebase.secure-cookie=false","--razorpay.key.id=","--razorpay.key.secret=","--debug=false","--logging.level.root=INFO",
            "--logging.level.org.springframework.jdbc=INFO","--logging.level.org.springframework.web=INFO",
            "--app.security.key-directory="+java.nio.file.Files.createTempDirectory("spin-browser-keys-"));
        System.out.println("SPIN_BROWSER_READY");
        try{new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();}finally{context.close();}
    }
}
