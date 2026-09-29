package com.iare.hackathon.withdrawal;

import com.iare.hackathon.HackathonApplication;
import com.iare.hackathon.catalog.*;
import com.iare.hackathon.commerce.*;
import com.iare.hackathon.support.SupportRepository;
import com.iare.hackathon.spin.SpinRepository;
import com.iare.hackathon.phototask.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;

/** Full user-interface fixture; all records live in a disposable local database. */
public class RedesignBrowserFixture {
 @TestConfiguration static class Fixture extends WithdrawalBrowserFixture.Fixture {
  @Bean SupportRepository supportFixture(JdbcTemplate jdbc){return new SupportRepository(jdbc);}
  @Bean SpinRepository spinFixture(JdbcTemplate jdbc){return new SpinRepository(jdbc);}
  @Bean MachineRewardRepository rewardsFixture(JdbcTemplate jdbc){return new MachineRewardRepository(jdbc);}
  @Bean PhotoTaskRepository photoFixture(JdbcTemplate jdbc){return new PhotoTaskRepository(jdbc);}
 }
 public static void main(String[] args)throws Exception{
  var context=new SpringApplication(HackathonApplication.class,Fixture.class).run(
   "--server.address=127.0.0.1","--server.port=8097","--app.firebase.enabled=false","--app.supabase.enabled=false",
   "--app.firebase.secure-cookie=false","--razorpay.key.id=","--razorpay.key.secret=",
   "--app.admin.email=admin@example.invalid","--app.admin.password=browser-test-password","--debug=false",
   "--logging.level.root=INFO","--app.security.key-directory="+java.nio.file.Files.createTempDirectory("redesign-test-keys-"));
  var catalog=context.getBean(CatalogRepository.class);var commerce=context.getBean(CommerceService.class);
  for(int i=1;i<=4;i++){
   var id=UUID.randomUUID();catalog.saveProduct(id,new ProductInput("Test product "+i,"/assets/machine-"+((i-1)%3+1)+".svg",new BigDecimal("300"),new BigDecimal("200"),40,new BigDecimal("20"),new BigDecimal("25"),"Disposable product for responsive interface verification.",true),true);
   if(i<=2){var purchase=commerce.buy("withdraw-browser-user",new CommerceDtos.Buy(id)).purchase();if(i==1)commerce.claim("withdraw-browser-user",purchase.id());}
  }
  System.out.println("REDESIGN_BROWSER_READY");
  try{new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();}finally{context.close();}
 }
}
