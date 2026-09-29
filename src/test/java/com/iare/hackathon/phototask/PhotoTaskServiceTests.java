package com.iare.hackathon.phototask;
import com.iare.hackathon.support.CloudinaryUploader;
import com.iare.hackathon.withdrawal.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PhotoTaskServiceTests {
 static EmbeddedPostgres db;static JdbcTemplate jdbc;PhotoTaskRepository repo;WithdrawalRepository wallet;MachineRewardRepository rewards;MachineRewardService rewardService;CloudinaryUploader media;WithdrawalEvents events;PhotoTaskService service;
 static MockMultipartFile photo() throws Exception {var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);return new MockMultipartFile("photo","photo.png","image/png",bytes.toByteArray());}
 @BeforeAll static void database()throws Exception{db=EmbeddedPostgres.builder().setPort(0).start();Flyway.configure().dataSource(db.getPostgresDatabase()).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(db.getPostgresDatabase());}
 @AfterAll static void close()throws Exception{if(db!=null)db.close();}
 @BeforeEach void setup(){jdbc.execute("TRUNCATE public.users CASCADE");for(String uid:List.of("alice","bob"))jdbc.update("INSERT INTO public.users(firebase_uid,email,name,provider,firebase_created_at,last_login_at,wallet_balance_paise) VALUES(?,?,?,'google.com',1000,CURRENT_TIMESTAMP,50000)",uid,uid+"@example.invalid",uid);
  UUID product=UUID.randomUUID(),purchase=UUID.randomUUID();jdbc.update("INSERT INTO public.products(id,title,image_url,original_price,discount_price,duration_days,daily_income,minimum_daily_income,total_earnings,description,start_at,end_at,active) VALUES(?,'Eligibility product','https://example.invalid/product.png',100,100,1,1,1,1,'Test product',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '2 days',true)",product);jdbc.update("INSERT INTO app_private.product_purchases(id,user_id,product_id,product_title,image_url,purchase_amount_paise,daily_income_paise,minimum_daily_income_paise,duration_days,configured_start_at,claim_zone,claim_time,razorpay_payment_id,payment_status,paid_at,first_claim_at,end_at) VALUES(?,'alice',?,'Eligibility product','https://example.invalid/product.png',10000,100,100,1,CURRENT_TIMESTAMP,'UTC','00:00','pay_eligibility','PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day')",purchase,product);
  jdbc.update("UPDATE public.task_machines SET active=true WHERE task_type='TAKE_PHOTO'");repo=spy(new PhotoTaskRepository(jdbc));wallet=new WithdrawalRepository(jdbc);rewards=spy(new MachineRewardRepository(jdbc));media=mock(CloudinaryUploader.class);events=spy(new WithdrawalEvents());when(media.uploadTaskPhoto(any(),any())).thenAnswer(i->new CloudinaryUploader.TaskAsset("https://res.cloudinary.com/test/image/authenticated/"+i.getArgument(1)+".jpg",i.getArgument(1)));when(media.taskPhotoUrl(any())).thenReturn("https://example.invalid/private-preview");var beans=new StaticListableBeanFactory();beans.addBean("repo",repo);beans.addBean("wallet",wallet);beans.addBean("rewards",rewards);service=new PhotoTaskService(beans.getBeanProvider(PhotoTaskRepository.class),beans.getBeanProvider(WithdrawalRepository.class),beans.getBeanProvider(MachineRewardRepository.class),media,events);rewardService=new MachineRewardService(beans.getBeanProvider(MachineRewardRepository.class),beans.getBeanProvider(WithdrawalRepository.class),events);
 }
 long balance(){return wallet.dashboard("alice",false).availableWinningPaise();}
 long credits(){return jdbc.queryForObject("SELECT count(*) FROM app_private.winning_transactions",Long.class);}
 @Test void uploadPendingThenApprovalWaitsForClaimAndPersistsReceipt()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());assertEquals("PENDING",task.status());assertEquals(0,balance());assertFalse(service.state("alice").eligible());
   var result=service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin@example.test");assertEquals("COMPLETED",result.status());assertEquals(0,balance());assertNull(result.ledgerId());assertEquals(0,credits());assertEquals("COMPLETED",rewards.list("alice").get(0).status());
  assertEquals(result,new PhotoTaskRepository(jdbc).one(task.id(),"alice",false));assertFalse(service.state("alice").eligible());var claim=rewardService.claim("alice",rewards.list("alice").get(0).id());assertEquals(2000,claim.reward().amountPaise());assertEquals(2000,claim.availableWinningPaise());assertFalse(claim.alreadyClaimed());assertTrue(rewardService.claim("alice",claim.reward().id()).alreadyClaimed());assertEquals(2000,balance());assertEquals(1,credits());assertEquals("machine-reward:TAKE_PHOTO:"+task.id(),jdbc.queryForObject("SELECT source_id FROM app_private.winning_transactions",String.class));assertEquals("CLAIMED",rewards.list("alice").get(0).status());assertEquals(50000,jdbc.queryForObject("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid='alice'",Long.class));
  assertThrows(ResponseStatusException.class,()->service.submit("alice",UUID.randomUUID(),photo()));assertThrows(ResponseStatusException.class,()->service.review(task.id(),new PhotoTaskDtos.Review("REJECTED","Wrong"),"admin"));
 }
 @Test void concurrentApprovalAndRetriesCreditOnce()throws Exception{
    var task=service.submit("alice",UUID.randomUUID(),photo());var reviewed=service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin");UUID reward=rewards.list("alice").get(0).id();var pool=Executors.newFixedThreadPool(6);try{var jobs=new ArrayList<Callable<UUID>>();for(int i=0;i<8;i++)jobs.add(()->rewardService.claim("alice",reward).reward().ledgerId());Set<UUID> ids=new HashSet<>();for(var f:pool.invokeAll(jobs))ids.add(f.get());assertEquals(1,ids.size());assertEquals("COMPLETED",reviewed.status());assertEquals(2000,balance());assertEquals(1,credits());}finally{pool.shutdownNow();}
 }
 @Test void rejectionHasReasonAndAllowsFreshSubmissionWithoutCredit()throws Exception{
  var first=service.submit("alice",UUID.randomUUID(),photo());assertThrows(ResponseStatusException.class,()->service.review(first.id(),new PhotoTaskDtos.Review("REJECTED"," "),"admin"));
  var rejected=service.review(first.id(),new PhotoTaskDtos.Review("REJECTED","Please show Launchpad on the mobile screen."),"admin");assertNotNull(rejected.rejectionReason());assertEquals(0,balance());assertEquals(0,credits());assertTrue(service.state("alice").eligible());
  assertEquals(rejected,service.review(first.id(),new PhotoTaskDtos.Review("REJECTED","Retry"),"admin"));var second=service.submit("alice",UUID.randomUUID(),photo());assertNotEquals(first.id(),second.id());assertEquals(2,service.state("alice").tasks().size());
 }
 @Test void identicalUploadRetryReturnsReceiptAndDifferentPhotoCannotReuseRequest()throws Exception{
  UUID request=UUID.randomUUID();var first=service.submit("alice",request,photo());assertEquals(first,service.submit("alice",request,photo()));verify(media,times(1)).uploadTaskPhoto(any(),any());
  byte[] changed=Arrays.copyOf(photo().getBytes(),photo().getBytes().length+1);assertThrows(ResponseStatusException.class,()->service.submit("alice",request,new MockMultipartFile("photo","changed.png","image/png",changed)));
 }
 @Test void approvalFailureRollsBackBothBalanceAndLedger()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());doThrow(new DataAccessResourceFailureException("Test outage")).when(repo).review(any(),any(),any(),any());assertThrows(DataAccessResourceFailureException.class,()->service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin"));assertEquals(0,balance());assertEquals(0,credits());assertEquals("PENDING",repo.one(task.id(),"alice",false).status());
 }
 @Test void orphanAndWrongAmountCreditsCannotCommit()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());assertThrows(RuntimeException.class,()->wallet.transaction(()->{wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",2000,"admin","take-photo:"+UUID.randomUUID());return null;}));
  assertThrows(RuntimeException.class,()->wallet.transaction(()->{wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",3000,"admin","take-photo:"+task.id());repo.review(task,"COMPLETED",null,"admin");return null;}));assertEquals(0,balance());assertEquals(0,credits());
 }
 @Test void ownershipAndInactiveMachineAreEnforced()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());assertTrue(service.state("bob").tasks().isEmpty());assertThrows(ResponseStatusException.class,()->service.image(task.id(),"bob"));assertNotNull(service.image(task.id(),"alice"));
  jdbc.update("UPDATE public.task_machines SET active=false WHERE task_type='TAKE_PHOTO'");assertFalse(service.state("alice").machineActive());assertThrows(ResponseStatusException.class,()->service.submit("bob",UUID.randomUUID(),photo()));assertNotNull(service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin"));
 }
 @Test void purchaseEligibilityIsEnforcedOnTheServer()throws Exception{assertFalse(service.state("bob").purchased());assertFalse(service.state("bob").eligible());var error=assertThrows(ResponseStatusException.class,()->service.submit("bob",UUID.randomUUID(),photo()));assertTrue(error.getReason().contains("Purchase any product to unlock this task."));assertTrue(service.state("bob").tasks().isEmpty());}
 @Test void cloudFailureCreatesNoTaskAndDatabaseFailureCleansUnreferencedUpload()throws Exception{
  when(media.uploadTaskPhoto(any(),any())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Cloud unavailable"));assertThrows(ResponseStatusException.class,()->service.submit("alice",UUID.randomUUID(),photo()));assertTrue(service.state("alice").tasks().isEmpty());reset(media);when(media.uploadTaskPhoto(any(),any())).thenAnswer(i->new CloudinaryUploader.TaskAsset("https://example.invalid/photo",i.getArgument(1)));
  doThrow(new DataAccessResourceFailureException("Test failure")).when(repo).insert(any(),any(),any(),any(),any(),any(),any());assertThrows(DataAccessResourceFailureException.class,()->service.submit("alice",UUID.randomUUID(),photo()));verify(media).deleteTaskPhoto(any());assertEquals(0,credits());
 }
 @Test void concurrentSubmissionsLeaveOnePendingTaskAndRemoveUnusedUpload()throws Exception{
  UUID request=UUID.randomUUID();var pool=Executors.newFixedThreadPool(3);try{var jobs=List.<Callable<UUID>>of(()->service.submit("alice",request,photo()).id(),()->service.submit("alice",request,photo()).id(),()->service.submit("alice",request,photo()).id());Set<UUID> ids=new HashSet<>();for(var f:pool.invokeAll(jobs))ids.add(f.get());assertEquals(1,ids.size());assertEquals(1,service.state("alice").tasks().size());assertEquals(0,balance());}finally{pool.shutdownNow();}
 }

 @Test void claimFailureRollsBackCreditAndDoesNotConsumeApproval()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin");var reward=rewards.list("alice").get(0);
  doThrow(new DataAccessResourceFailureException("Test rollback")).when(rewards).claim(any(),any(),any());assertThrows(DataAccessResourceFailureException.class,()->rewardService.claim("alice",reward.id()));assertEquals(0,balance());assertEquals(0,credits());assertEquals("COMPLETED",rewards.list("alice").get(0).status());assertThrows(ResponseStatusException.class,()->rewardService.claim("bob",reward.id()));
 }
 @Test void repeatedConcurrentApprovalsOfferOneRewardWithoutCrediting()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Callable<UUID>>();for(int i=0;i<6;i++)jobs.add(()->service.review(task.id(),new PhotoTaskDtos.Review("COMPLETED",null),"admin").id());for(var job:pool.invokeAll(jobs))assertEquals(task.id(),job.get());}finally{pool.shutdownNow();}assertEquals(1,rewards.list("alice").size());assertEquals(0,balance());assertEquals(0,credits());
 }
 @Test void forgedRewardAndOrphanClaimCreditAreRejected()throws Exception{
  var task=service.submit("alice",UUID.randomUUID(),photo());assertThrows(RuntimeException.class,()->rewards.createPhoto(task.id(),"alice"));assertThrows(RuntimeException.class,()->wallet.transaction(()->{wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",2000,"test","machine-reward:TAKE_PHOTO:"+task.id());return null;}));assertEquals(0,balance());assertEquals(0,credits());
 }
}
