package com.iare.hackathon.phototask;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.iare.hackathon.withdrawal.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class MachineRewardMigrationTests {
 @Test void legacyPaidPhotoCannotBeClaimedAgainAndMigrationIsRepeatable()throws Exception{
  try(var db=EmbeddedPostgres.builder().setPort(0).start()){
   var source=db.getPostgresDatabase();Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").target("18").load().migrate();var jdbc=new JdbcTemplate(source);
   jdbc.update("INSERT INTO public.users(firebase_uid,name,email,provider,firebase_created_at,last_login_at) VALUES('legacy','Legacy','legacy@example.invalid','google.com',1000,CURRENT_TIMESTAMP)");
   UUID task=UUID.randomUUID();UUID machine=jdbc.queryForObject("SELECT id FROM public.task_machines WHERE task_type='TAKE_PHOTO'",UUID.class);
   jdbc.update("INSERT INTO app_private.photo_tasks(id,machine_id,user_id,request_id,photo_url,cloudinary_public_id,photo_sha256) VALUES(?,?,'legacy',?,'https://example.invalid/photo','private/legacy',?)",task,machine,UUID.randomUUID(),"0".repeat(64));
   var wallet=new WithdrawalRepository(jdbc);wallet.transaction(()->{wallet.lockUser("legacy");wallet.ledger("legacy",null,"EARNING",2000,"old-admin","take-photo:"+task);jdbc.update("UPDATE app_private.photo_tasks SET status='COMPLETED',reviewed_by='old-admin',reviewed_at=CURRENT_TIMESTAMP,ledger_id=(SELECT id FROM app_private.winning_transactions WHERE source_id=?) WHERE id=?","take-photo:"+task,task);return null;});
   var rewards=new MachineRewardRepository(jdbc);rewards.createPhoto(task,"legacy");
   Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();
   Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();
   var beans=new StaticListableBeanFactory();beans.addBean("wallet",wallet);beans.addBean("rewards",rewards);var service=new MachineRewardService(beans.getBeanProvider(MachineRewardRepository.class),beans.getBeanProvider(WithdrawalRepository.class),new WithdrawalEvents());
   var reward=rewards.list("legacy").get(0);assertEquals("CLAIMED",reward.status());assertTrue(service.claim("legacy",reward.id()).alreadyClaimed());assertEquals(2000,wallet.dashboard("legacy",false).availableWinningPaise());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_private.winning_transactions",Integer.class));
   assertThrows(RuntimeException.class,()->rewards.createPhoto(task,"legacy"));
  }
 }

 @Test void legacyReferralAlreadyPaidDoesNotLeaveAnExtraClaim()throws Exception{
  try(var db=EmbeddedPostgres.builder().setPort(0).start()){
   var source=db.getPostgresDatabase();Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").target("18").load().migrate();var jdbc=new JdbcTemplate(source);
   for(String uid:java.util.List.of("inviter","friend")){jdbc.update("INSERT INTO public.users(firebase_uid,name,email,provider,firebase_created_at,last_login_at) VALUES(?,?,?,'google.com',1000,CURRENT_TIMESTAMP)",uid,uid,uid+"@example.invalid");UUID p=UUID.randomUUID();jdbc.update("INSERT INTO public.products(id,title,image_url,original_price,discount_price,duration_days,daily_income,minimum_daily_income,total_earnings,description,start_at,end_at,active) VALUES(?,'Test','https://example.invalid/p',100,100,1,1,1,1,'Test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '2 days',true)",p);jdbc.update("INSERT INTO app_private.product_purchases(id,user_id,product_id,product_title,image_url,purchase_amount_paise,daily_income_paise,minimum_daily_income_paise,duration_days,configured_start_at,claim_zone,claim_time,razorpay_payment_id,payment_status,paid_at,first_claim_at,end_at) VALUES(?,?,?,'Test','https://example.invalid/p',10000,100,100,1,CURRENT_TIMESTAMP,'UTC','00:00',?,'PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day')",UUID.randomUUID(),uid,p,"pay_"+uid);}
   UUID referral=UUID.randomUUID();jdbc.update("INSERT INTO app_private.referrals(id,inviter_id,invitee_id,referral_code) SELECT ?,'inviter','friend',referral_code FROM public.users WHERE firebase_uid='inviter'",referral);
   var wallet=new WithdrawalRepository(jdbc);wallet.transaction(()->{wallet.lockUser("inviter");wallet.ledger("inviter",null,"EARNING",5000,"old","referral:"+referral+":INVITER");jdbc.update("INSERT INTO app_private.referral_rewards(id,referral_id,purchase_id,beneficiary_id,role,ledger_id) SELECT ?,?,p.id,'inviter','INVITER',w.id FROM app_private.product_purchases p,app_private.winning_transactions w WHERE p.user_id='friend' AND w.user_id='inviter'",UUID.randomUUID(),referral);return null;});
   var rewards=new MachineRewardRepository(jdbc);rewards.createReferral(referral,"inviter");UUID reward=rewards.list("inviter").get(0).id();
   Flyway.configure().dataSource(source).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();
   assertTrue(rewards.list("inviter").isEmpty());var beans=new StaticListableBeanFactory();beans.addBean("wallet",wallet);beans.addBean("rewards",rewards);var service=new MachineRewardService(beans.getBeanProvider(MachineRewardRepository.class),beans.getBeanProvider(WithdrawalRepository.class),new WithdrawalEvents());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.claim("inviter",reward));assertEquals(5000,wallet.dashboard("inviter",false).availableWinningPaise());assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT status FROM app_private.machine_reward_claims WHERE id=?",String.class,reward));
  }
 }
}
