package com.iare.hackathon.phototask;
import com.google.firebase.auth.FirebaseToken;
import com.iare.hackathon.HackathonApplication;
import com.iare.hackathon.auth.*;
import com.iare.hackathon.catalog.CatalogRepository;
import com.iare.hackathon.user.*;
import com.iare.hackathon.withdrawal.WithdrawalRepository;
import com.iare.hackathon.support.CloudinaryUploader;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
/** Isolated browser fixture: real PostgreSQL/ledger, test authentication and Cloudinary boundary. */
public class PhotoTaskBrowserFixture {
 @TestConfiguration static class Fixture {
  @Bean(destroyMethod="close") EmbeddedPostgres photoPostgres()throws Exception{return EmbeddedPostgres.builder().setPort(0).start();}
  @Bean JdbcTemplate photoJdbc(EmbeddedPostgres pg){var source=pg.getPostgresDatabase();Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();var jdbc=new JdbcTemplate(source);for(String uid:List.of("photo-alice","photo-bob"))new JdbcUserProfileRepository(jdbc).upsert(new AuthUser(uid,uid,uid+"@example.invalid",null,true,1000,2000,"google.com"));jdbc.update("UPDATE public.users SET wallet_balance_paise=1000000");
   UUID product=UUID.fromString("10000000-0000-4000-8000-000000000001");new CatalogRepository(jdbc).saveProduct(product,new com.iare.hackathon.catalog.ProductInput("Eligibility product","/assets/machine-1.svg",new java.math.BigDecimal("100"),new java.math.BigDecimal("100"),30,new java.math.BigDecimal("1"),"Test product",true),true);return jdbc;}
  @Bean com.iare.hackathon.commerce.CommerceRepository photoCommerce(JdbcTemplate jdbc){return new com.iare.hackathon.commerce.CommerceRepository(jdbc);}
  @Bean com.iare.hackathon.wallet.WalletRepository photoWallet(JdbcTemplate jdbc){return new com.iare.hackathon.wallet.WalletRepository(jdbc);}
  @Bean MachineRewardRepository photoRewards(JdbcTemplate jdbc){return new MachineRewardRepository(jdbc);}
  @Bean PhotoTaskRepository photoRepo(JdbcTemplate jdbc){return new PhotoTaskRepository(jdbc);}
  @Bean WithdrawalRepository photoWinning(JdbcTemplate jdbc){return new WithdrawalRepository(jdbc);}
  @Bean CatalogRepository photoCatalog(JdbcTemplate jdbc){return new CatalogRepository(jdbc);}
  @Bean UserProfileRepository photoProfiles(JdbcTemplate jdbc){return new JdbcUserProfileRepository(jdbc);}
  @Bean @Primary CloudinaryUploader photoCloud(){var media=mock(CloudinaryUploader.class);when(media.uploadTaskPhoto(any(),any())).thenAnswer(i->new CloudinaryUploader.TaskAsset("https://res.cloudinary.com/test/image/authenticated/"+i.getArgument(1)+".jpg",i.getArgument(1)));when(media.taskPhotoUrl(any())).thenReturn("http://127.0.0.1:8099/assets/machine-1.svg");return media;}
  @Bean @Primary FirebaseAuthService photoAuth()throws Exception{var auth=mock(FirebaseAuthService.class);for(String uid:List.of("photo-alice","photo-bob")){var token=mock(FirebaseToken.class);when(token.getUid()).thenReturn(uid);when(auth.verifySession(uid+"-session")).thenReturn(token);}return auth;}
 }
 public static void main(String[] args)throws Exception{var app=new SpringApplication(HackathonApplication.class,Fixture.class);try(var context=app.run("--server.address=127.0.0.1","--server.port=8099","--app.firebase.enabled=false","--app.supabase.enabled=false","--app.firebase.secure-cookie=false","--app.admin.email=photo-admin@example.test","--app.admin.password=photo-test-password","--razorpay.key.id=","--razorpay.key.secret=","--debug=false","--logging.level.root=INFO","--logging.level.org.springframework.jdbc=INFO","--logging.level.org.springframework.web=INFO","--app.security.key-directory="+java.nio.file.Files.createTempDirectory("photo-browser-keys-"))){System.out.println("PHOTO_BROWSER_READY");new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();}}
}
