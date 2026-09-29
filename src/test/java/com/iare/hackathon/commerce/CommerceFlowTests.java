package com.iare.hackathon.commerce;

import static com.iare.hackathon.commerce.CommerceDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.iare.hackathon.catalog.*;
import com.iare.hackathon.phototask.*;
import com.iare.hackathon.wallet.*;
import com.iare.hackathon.withdrawal.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class CommerceFlowTests {
    static EmbeddedPostgres postgres;static JdbcTemplate jdbc;static ValidatorFactory validation;
    static class MutableClock extends Clock { Instant time=Instant.parse("2026-09-24T06:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;} }
    MutableClock clock;CommerceService service;CommerceRepository repository;WithdrawalService withdrawals;WalletRepository wallet;MachineRewardRepository machineRewards;ProductPaymentGateway payments;BankEncryption encryption;
    @TempDir java.nio.file.Path keyDirectory;
    @BeforeAll static void database()throws Exception{postgres=EmbeddedPostgres.builder().setPort(0).start();var ds=postgres.getPostgresDatabase();
        Flyway.configure().dataSource(ds).schemas("app_private").defaultSchema("app_private").createSchemas(true).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);validation=Validation.buildDefaultValidatorFactory();}
    @AfterAll static void close()throws Exception{if(validation!=null)validation.close();if(postgres!=null)postgres.close();}
    @BeforeEach void setup()throws Exception{
        jdbc.execute("TRUNCATE public.users,public.products CASCADE");
        for(String uid:List.of("alice","bob","carol"))jdbc.update("INSERT INTO public.users(firebase_uid,name,email,provider,firebase_created_at,last_login_at,wallet_balance_paise) VALUES (?,?,?,'google.com',1,CURRENT_TIMESTAMP,200000)",uid,uid,uid+"@example.invalid");
        clock=new MutableClock();repository=spy(new CommerceRepository(jdbc));wallet=new WalletRepository(jdbc);machineRewards=new MachineRewardRepository(jdbc);payments=mock(ProductPaymentGateway.class);
        var beans=new StaticListableBeanFactory();beans.addBean("commerce",repository);beans.addBean("wallet",wallet);beans.addBean("machineRewards",machineRewards);beans.addBean("jdbc",jdbc);beans.addBean("withdrawals",new WithdrawalRepository(jdbc));
        encryption=new BankEncryption(new BankKeyStore(new MockEnvironment().withProperty("app.security.key-directory",keyDirectory.toString()),beans.getBeanProvider(JdbcTemplate.class)));
        withdrawals=new WithdrawalService(beans.getBeanProvider(WithdrawalRepository.class),new BankDirectory(),encryption,validation.getValidator());
        service=new CommerceService(beans.getBeanProvider(CommerceRepository.class),payments,beans.getBeanProvider(WalletRepository.class),withdrawals,new CommerceConfiguration.ClaimSchedule(ZoneId.of("Asia/Kolkata"),LocalTime.MIDNIGHT),clock,validation.getValidator(),beans.getBeanProvider(MachineRewardRepository.class));
    }
    UUID product(int days,String daily){UUID id=UUID.randomUUID();new CatalogRepository(jdbc).saveProduct(id,new ProductInput("Machine A","https://example.invalid/product.png",new BigDecimal("1000"),new BigDecimal("1000"),days,new BigDecimal(daily),"Daily claim product",true),true);return id;}
    Purchase buy(String uid,UUID id){return service.buy(uid,new Buy(id)).purchase();}
    long winning(String uid){return withdrawals.dashboard(uid).availableWinningPaise();}
    int count(String table){return jdbc.queryForObject("SELECT count(*) FROM app_private."+table,Integer.class);}
    WithdrawalDtos.BankAccount bank(){return withdrawals.addBank("alice",new WithdrawalDtos.BankInput("SBIN","Test Holder","12345678901","12345678901","SBIN0000001","Personal"));}
    @Test void purchaseDebitsPrincipalExactlyOnceAndNeverCreditsWinnings(){UUID product=product(45,"1");var first=buy("alice",product);var rejected=assertThrows(ResponseStatusException.class,()->buy("alice",product));
        assertEquals(409,rejected.getStatusCode().value());assertEquals("PAID",first.paymentStatus());assertEquals(100000,wallet.balance("alice"));assertEquals(0,winning("alice"));assertEquals(1,count("wallet_transactions"));assertTrue(wallet.history("alice").isEmpty());assertEquals(1,new CatalogRepository(jdbc).product(product,false).orElseThrow().soldCount());}
    @Test void ownershipIsPersonalAndDatabaseRejectsDuplicateEvenAfterCompletion(){
        UUID id=product(1,"20");var catalog=new CatalogRepository(jdbc);
        assertTrue(catalog.purchasesForUser("alice").isEmpty());var a=buy("alice",id);
        assertEquals(Map.of(id,a.id()),catalog.purchasesForUser("alice"));assertTrue(catalog.purchasesForUser("bob").isEmpty());
        var b=buy("bob",id);assertNotEquals(a.id(),b.id());assertEquals(Map.of(id,b.id()),catalog.purchasesForUser("bob"));
        service.claim("alice",a.id());assertEquals(409,assertThrows(ResponseStatusException.class,()->buy("alice",id)).getStatusCode().value());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->repository.transaction(()->repository.intent("alice",repository.terms(id),new CommerceConfiguration.ClaimSchedule(ZoneId.of("Asia/Kolkata"),LocalTime.MIDNIGHT))));
        assertEquals(2,count("product_purchases"));assertEquals(100000,wallet.balance("alice"));assertEquals(100000,wallet.balance("bob"));assertEquals(2000,winning("alice"));
    }
    @Test void claimUsesTimezoneAndUpdatesProgressAndOnlyWinningCash(){var p=buy("alice",product(45,"1"));assertEquals(clock.instant(),p.startDate());
        clock.time=p.startDate().minusMillis(1);assertThrows(ResponseStatusException.class,()->service.claim("alice",p.id()));clock.time=p.startDate();
        var result=service.claim("alice",p.id());assertEquals(100,result.amountPaise());assertEquals(100,winning("alice"));assertEquals(100000,wallet.balance("alice"));
        assertEquals(new BigDecimal("2.22"),result.purchase().progressPercent());assertEquals(4400,result.purchase().remainingEarningsPaise());assertEquals(0,result.purchase().claimablePaise());assertEquals(1,result.purchase().claimedDays());
        assertTrue(service.claim("alice",p.id()).alreadyClaimed());assertEquals(1,count("product_daily_claims"));assertEquals(1,count("winning_transactions"));}
    @Test void finalClaimCompletesProductImmediatelyAndCannotOverpay(){var p=buy("alice",product(2,"100"));clock.time=p.startDate();service.claim("alice",p.id());clock.time=p.startDate().plusSeconds(86400);
        var last=service.claim("alice",p.id());assertEquals("COMPLETED",last.purchase().status());assertEquals(new BigDecimal("100.00"),last.purchase().progressPercent());assertTrue(service.claim("alice",p.id()).alreadyClaimed());assertEquals(20000,winning("alice"));
        assertEquals(1,service.purchases("alice",new Filter(null,null,null,null,null,null,"COMPLETED",null,null)).total());assertEquals(0,service.purchases("alice",new Filter(null,null,null,null,null,null,"ACTIVE",null,null)).total());
        assertEquals(1,service.sales(new Filter(null,null,null,null,null,null,null,null,null)).items().get(0).completedPurchases());
        clock.time=p.endDate();assertThrows(ResponseStatusException.class,()->service.claim("alice",p.id()));assertEquals(20000,winning("alice"));}
    @Test void missedClaimsExpireAndCompletionDoesNotInventIncome(){var p=buy("alice",product(2,"100"));clock.time=p.startDate().plusSeconds(86400);var c=service.claim("alice",p.id());assertEquals(2,c.dayNumber());assertEquals(10000,c.purchase().missedIncomePaise());clock.time=p.endDate();var ended=service.detail("alice",p.id()).purchase();assertEquals("COMPLETED",ended.status());assertEquals(new BigDecimal("50.00"),ended.progressPercent());assertEquals(10000,winning("alice"));assertThrows(ResponseStatusException.class,()->service.claim("alice",p.id()));}
    @Test void simultaneousClaimsCreditOnce()throws Exception{var p=buy("alice",product(2,"100"));clock.time=p.startDate();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<ClaimResult> work=()->{gate.await();return service.claim("alice",p.id());};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS).alreadyClaimed(),b.get(15,TimeUnit.SECONDS).alreadyClaimed());assertEquals(10000,winning("alice"));assertEquals(1,count("product_daily_claims"));}finally{pool.shutdownNow();}}
    @Test void insufficientPrincipalAndInactiveProductCannotBeBought(){UUID id=product(2,"100");jdbc.update("UPDATE public.users SET wallet_balance_paise=100 WHERE firebase_uid='alice'");assertThrows(ResponseStatusException.class,()->buy("alice",id));assertEquals(0,count("product_purchases"));assertEquals(0,count("wallet_transactions"));jdbc.update("UPDATE public.products SET active=false WHERE id=?",id);assertThrows(ResponseStatusException.class,()->buy("bob",id));}
    @Test void ownershipIsEnforcedAndProductEditsDoNotChangeEarnings(){var id=product(2,"100");var p=buy("alice",id);jdbc.update("UPDATE public.products SET daily_income=999 WHERE id=?",id);clock.time=p.startDate();assertThrows(ResponseStatusException.class,()->service.claim("bob",p.id()));assertThrows(ResponseStatusException.class,()->service.detail("bob",p.id()));assertEquals(10000,service.claim("alice",p.id()).amountPaise());assertThrows(org.springframework.dao.DataAccessException.class,()->new CatalogRepository(jdbc).deleteProduct(id));}
    @Test void referralCreatesOnlyOneUncreditedClaimAfterPaidPurchase(){buy("bob",product(2,"1"));String code=repository.code("bob");service.bind("alice",new Bind(code));assertEquals(0,winning("alice"));assertEquals(0,winning("bob"));UUID id=product(2,"1");buy("alice",id);assertThrows(ResponseStatusException.class,()->buy("alice",id));assertEquals(0,winning("alice"));assertEquals(0,winning("bob"));assertEquals(0,count("referral_rewards"));assertEquals(0,count("winning_transactions"));assertEquals(1,count("machine_reward_claims"));assertEquals(1,service.invitations("bob",new ReferralFilter(null,null,null,null,null,null,null,null,null,null)).successfulInvitations());}
    @Test void eligibleInviterGetsOneClaimableThirtyRupeeRewardAfterReferralPurchase(){
        buy("alice",product(2,"1"));service.bind("bob",new Bind(repository.code("alice")));buy("bob",product(2,"1"));
        var claims=machineRewards.list("alice");assertEquals(1,claims.size());assertEquals("REFERRAL",claims.get(0).type());assertEquals(3000,claims.get(0).amountPaise());assertEquals("COMPLETED",claims.get(0).status());assertEquals(0,winning("alice"));
        var beans=new StaticListableBeanFactory();beans.addBean("rewards",machineRewards);beans.addBean("wallet",new WithdrawalRepository(jdbc));var rewardService=new MachineRewardService(beans.getBeanProvider(MachineRewardRepository.class),beans.getBeanProvider(WithdrawalRepository.class),new WithdrawalEvents());
        var claimed=rewardService.claim("alice",claims.get(0).id());assertEquals(3000,claimed.availableWinningPaise());assertEquals(3000,claimed.reward().amountPaise());assertFalse(claimed.alreadyClaimed());assertTrue(rewardService.claim("alice",claims.get(0).id()).alreadyClaimed());
        assertEquals(3000,winning("alice"));assertEquals(1,machineRewards.list("alice").size());assertEquals(1,count("winning_transactions"));
    }
    @Test void inviterWithoutPurchaseCannotReceiveReferralMachineReward(){assertThrows(ResponseStatusException.class,()->service.bind("bob",new Bind(repository.code("alice"))));buy("bob",product(2,"1"));assertTrue(machineRewards.list("alice").isEmpty());assertFalse(service.invitations("alice",new ReferralFilter(null,null,null,null,null,null,null,null,null,null)).eligibleForReferAndEarn());}
    @Test void invalidSelfChangedAndLateReferralBindingsAreRejected(){assertThrows(ResponseStatusException.class,()->service.bind("alice",new Bind(repository.code("alice"))));assertThrows(ResponseStatusException.class,()->service.bind("alice",new Bind("F".repeat(32))));buy("bob",product(2,"1"));service.bind("alice",new Bind(repository.code("bob")));assertThrows(ResponseStatusException.class,()->service.bind("alice",new Bind(repository.code("carol"))));assertThrows(ResponseStatusException.class,()->service.bind("bob",new Bind(repository.code("alice"))));buy("carol",product(2,"1"));assertThrows(ResponseStatusException.class,()->service.bind("carol",new Bind(repository.code("bob"))));}
    @Test void failedPurchaseCreatesNoReferralRewards(){buy("bob",product(2,"1"));service.bind("alice",new Bind(repository.code("bob")));jdbc.update("UPDATE public.users SET wallet_balance_paise=0 WHERE firebase_uid='alice'");assertThrows(ResponseStatusException.class,()->buy("alice",product(2,"100")));assertEquals(0,count("referral_rewards"));assertEquals(0,winning("alice"));assertEquals(0,winning("bob"));}
    @Test void dailyClaimFailureRollsBackWinningCredit(){var p=buy("alice",product(2,"100"));clock.time=p.startDate();doThrow(new IllegalStateException("test rollback")).when(repository).claim(any(),anyInt(),any(),any(),any());assertThrows(IllegalStateException.class,()->service.claim("alice",p.id()));assertEquals(0,winning("alice"));assertEquals(0,count("winning_transactions"));}
    @Test void referralFailureRollsBackBothRewardsAndPurchase(){buy("bob",product(2,"1"));service.bind("alice",new Bind(repository.code("bob")));doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("test rollback after offer");}).when(repository).paidFromWallet(any(),any(),any(),any());assertThrows(IllegalStateException.class,()->buy("alice",product(2,"100")));assertEquals(200000,wallet.balance("alice"));assertEquals(0,winning("alice"));assertEquals(0,winning("bob"));assertEquals(0,count("referral_rewards"));assertEquals(1,count("product_purchases"));assertEquals(0,count("machine_reward_claims"));}
    @Test void claimedCashCanBeWithdrawnAndRefundedWithoutChangingPrincipal(){var p=buy("alice",product(2,"100"));clock.time=p.startDate();service.claim("alice",p.id());var bank=bank();assertTrue(encryption.configured());
        var request=new WithdrawalDtos.Create(bank.id(),new BigDecimal("100"),UUID.randomUUID());var w=withdrawals.create("alice",request);assertEquals(0,winning("alice"));assertEquals(100000,withdrawals.dashboard("alice").availableRechargePaise());assertEquals(w.id(),withdrawals.create("alice",request).id());
        assertEquals("12345678901",withdrawals.payoutDetails(w.id(),"admin").accountNumber());var action=new WithdrawalDtos.Action("FAILED",null,"Reviewed","No external transfer");withdrawals.process(w.id(),action,"admin");withdrawals.process(w.id(),action,"admin");assertEquals(10000,winning("alice"));assertEquals(100000,wallet.balance("alice"));}
    @Test void newBankKeyIsPersistentAndNoManualSecretIsRequired(){bank();var beans=new StaticListableBeanFactory();beans.addBean("jdbc",jdbc);var fresh=new BankEncryption(new BankKeyStore(new MockEnvironment().withProperty("app.security.key-directory",keyDirectory.toString()),beans.getBeanProvider(JdbcTemplate.class)));var id=withdrawals.banks("alice").get(0).id();String cipher=jdbc.queryForObject("SELECT account_ciphertext FROM app_private.bank_accounts WHERE id=?",String.class,id);assertFalse(cipher.contains("12345678901"));assertEquals("12345678901",fresh.decrypt(cipher,"alice:"+id));assertEquals(32,java.nio.file.Files.exists(keyDirectory.resolve("bank-key.bin"))?new BankKeyStore(new MockEnvironment().withProperty("app.security.key-directory",keyDirectory.toString()),beans.getBeanProvider(JdbcTemplate.class)).key().length:0);}
    @Test void withdrawalMinimumAndAmountFiltersAreAuthoritative(){var p=buy("alice",product(2,"100"));clock.time=p.startDate();service.claim("alice",p.id());var b=bank();assertThrows(ResponseStatusException.class,()->withdrawals.create("alice",new WithdrawalDtos.Create(b.id(),new BigDecimal("9.99"),UUID.randomUUID())));withdrawals.create("alice",new WithdrawalDtos.Create(b.id(),new BigDecimal("100"),UUID.randomUUID()));var page=withdrawals.list(null,new WithdrawalDtos.Filter(null,null,null,null,null,null,null,new BigDecimal("100"),new BigDecimal("100")));assertEquals(1,page.total());assertEquals(1,page.summary().processing());assertEquals(10000,page.summary().totalRequestedPaise());}
    UUID legacyOrder(){return repository.transaction(()->{var id=repository.intent("alice",repository.terms(product(2,"100")),new CommerceConfiguration.ClaimSchedule(ZoneId.of("Asia/Kolkata"),LocalTime.MIDNIGHT));repository.order(id,"order_Legacy");return id;});}
    @Test void webhookRequiresCapturedMatchingProviderProofAndIsIdempotent(){
        var id=legacyOrder();buy("carol",product(2,"1"));service.bind("bob",new Bind(repository.code("carol")));
        when(payments.payment("pay_Legacy")).thenReturn(new RazorpayGateway.Payment("pay_Legacy","order_Legacy",100000,"INR","authorized",false,0));
        service.webhookPayment("order_Legacy","pay_Legacy");assertEquals("PENDING",repository.row(id,"alice").paymentStatus());assertEquals(0,count("product_payment_transactions"));
        when(payments.payment("pay_Legacy")).thenReturn(new RazorpayGateway.Payment("pay_Legacy","order_Legacy",100000,"INR","captured",true,0));
        service.webhookPayment("order_Legacy","pay_Legacy");service.webhookPayment("order_Legacy","pay_Legacy");assertEquals("PAID",repository.row(id,"alice").paymentStatus());assertEquals(1,count("product_payment_transactions"));assertEquals(0,winning("alice"));assertEquals(200000,wallet.balance("alice"));
    }
    @Test void forgedMismatchedOrRefundedWebhookCannotActivatePurchase(){
        var id=legacyOrder();
        for(var remote:List.of(new RazorpayGateway.Payment("pay_Other","order_Legacy",100000,"INR","captured",true,0),new RazorpayGateway.Payment("pay_Legacy","order_Other",100000,"INR","captured",true,0),new RazorpayGateway.Payment("pay_Legacy","order_Legacy",1,"INR","captured",true,0),new RazorpayGateway.Payment("pay_Legacy","order_Legacy",100000,"USD","captured",true,0))){
            when(payments.payment("pay_Legacy")).thenReturn(remote);assertThrows(ResponseStatusException.class,()->service.webhookPayment("order_Legacy","pay_Legacy"));
        }
        when(payments.payment("pay_Legacy")).thenReturn(new RazorpayGateway.Payment("pay_Legacy","order_Legacy",100000,"INR","captured",true,100));service.webhookPayment("order_Legacy","pay_Legacy");
        assertEquals("PENDING",repository.row(id,"alice").paymentStatus());assertEquals(0,count("product_payment_transactions"));assertEquals(0,count("winning_transactions"));
        clearInvocations(payments);service.webhookPayment("order_Unknown","pay_Legacy");verifyNoInteractions(payments);
    }
    UUID rangeProduct(int days,String minimum,String maximum){
        UUID id=UUID.randomUUID();new CatalogRepository(jdbc).saveProduct(id,new ProductInput("Range product","https://example.invalid/product.png",new BigDecimal("1000"),new BigDecimal("1000"),days,new BigDecimal(minimum),new BigDecimal(maximum),"Daily income range",true),true);return id;
    }
    @Test void immediateFirstClaimThenExactIstMidnightAndStableDecimalAmounts(){
        clock.time=Instant.parse("2026-09-24T18:29:59Z");var p=buy("alice",rangeProduct(3,"20.01","20.99"));
        assertEquals(clock.instant(),p.startDate());assertTrue(p.todayProfitPaise()>=2001&&p.todayProfitPaise()<=2099);
        long amount=p.todayProfitPaise();
        for(int i=0;i<3;i++){assertEquals(amount,service.detail("alice",p.id()).purchase().todayProfitPaise());assertEquals(amount,service.purchases("alice",new Filter(null,null,null,null,null,null,null,null,null)).items().get(0).todayProfitPaise());}
        var first=service.claim("alice",p.id());assertEquals(amount,first.amountPaise());assertEquals(amount,winning("alice"));assertEquals(amount,first.purchase().todayProfitPaise());assertEquals(0,first.purchase().claimablePaise());
        assertEquals(Instant.parse("2026-09-24T18:30:00Z"),first.purchase().nextClaimAt());
        clock.time=Instant.parse("2026-09-24T18:29:59.999Z");assertTrue(service.claim("alice",p.id()).alreadyClaimed());
        clock.time=Instant.parse("2026-09-24T18:30:00Z");var second=service.claim("alice",p.id());assertEquals(2,second.dayNumber());assertTrue(second.amountPaise()>=2001&&second.amountPaise()<=2099);assertEquals(amount+second.amountPaise(),winning("alice"));
        clock.time=Instant.parse("2026-09-25T18:30:00Z");var third=service.claim("alice",p.id());assertEquals("COMPLETED",third.purchase().status());assertEquals(0,third.purchase().remainingEarningsPaise());assertTrue(service.claim("alice",p.id()).alreadyClaimed());
        assertEquals(amount+second.amountPaise()+third.amountPaise(),winning("alice"));assertEquals(100000,wallet.balance("alice"));
    }
    @Test void configuredRangeCannotBeChangedForPurchaseAndDatabaseRejectsTampering(){
        var product=rangeProduct(30,"20","30");var p=buy("alice",product);
        var amounts=repository.row(p.id(),"alice").amounts();assertEquals(30,amounts.size());assertTrue(amounts.stream().allMatch(a->a>=2000&&a<=3000));
        jdbc.update("UPDATE public.products SET minimum_daily_income=40,daily_income=50 WHERE id=?",product);
        assertEquals(amounts,repository.row(p.id(),"alice").amounts());assertEquals(2000,service.detail("alice",p.id()).purchase().minimumDailyIncomePaise());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE app_private.product_daily_amounts SET amount_paise=999999 WHERE purchase_id=?",p.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE app_private.product_purchases SET minimum_daily_income_paise=1 WHERE id=?",p.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO app_private.product_daily_amounts VALUES (?,31,2000)",p.id()));
    }
    @Test void equalDecimalBoundsAreExact(){var p=buy("alice",rangeProduct(1,"23.34","23.34"));assertEquals(2334,p.todayProfitPaise());assertEquals(2334,service.claim("alice",p.id()).amountPaise());assertEquals(2334,winning("alice"));}
    @Test void tenRupeeMinimumIsConfigurableAndReplaySurvivesConfigurationChange(){
        var p=buy("alice",rangeProduct(1,"100","100"));service.claim("alice",p.id());var b=bank();var request=new WithdrawalDtos.Create(b.id(),new BigDecimal("10"),UUID.randomUUID());
        assertEquals(1000,withdrawals.dashboard("alice").minimumAmountPaise());assertThrows(ResponseStatusException.class,()->withdrawals.create("alice",new WithdrawalDtos.Create(b.id(),new BigDecimal("9.99"),UUID.randomUUID())));
        var w=withdrawals.create("alice",request);assertEquals(9000,winning("alice"));withdrawals.setMinimumAmount(new BigDecimal("100"));assertEquals(10000,withdrawals.dashboard("alice").minimumAmountPaise());
        assertEquals(w.id(),withdrawals.create("alice",request).id());assertThrows(ResponseStatusException.class,()->withdrawals.create("alice",new WithdrawalDtos.Create(b.id(),new BigDecimal("10"),UUID.randomUUID())));
    }
    @Test void concurrentPurchaseOnlyDebitsOnce()throws Exception{
        UUID id=rangeProduct(2,"20","30");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<Integer> work=()->{gate.await();try{buy("alice",id);return 200;}catch(ResponseStatusException ex){return ex.getStatusCode().value();}};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertEquals(Set.of(200,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));assertEquals(100000,wallet.balance("alice"));assertEquals(1,count("wallet_transactions"));assertEquals(2,count("product_daily_amounts"));}finally{pool.shutdownNow();}
    }
    @Test void invalidAdminRangesFailBothValidationAndDatabase(){
        for(String[] bounds:List.of(new String[]{"0","30"},new String[]{"30","20"},new String[]{"-1","30"})){
            var input=new ProductInput("Bad","https://example.invalid/p.png",BigDecimal.TEN,BigDecimal.TEN,2,new BigDecimal(bounds[0]),new BigDecimal(bounds[1]),"Bad range",true);
            assertFalse(validation.getValidator().validate(input).isEmpty());assertThrows(org.springframework.dao.DataAccessException.class,()->new CatalogRepository(jdbc).saveProduct(UUID.randomUUID(),input,true));
        }
    }

    @Test void forgedClaimOutsideRangeRollsBackItsWinningLedger(){
        var p=buy("alice",rangeProduct(2,"20","30"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->repository.transaction(()->{
            String source="product-claim:"+p.id()+":1";withdrawals.creditEligibleEarning("alice",source,new BigDecimal("30.01"));
            jdbc.update("INSERT INTO app_private.product_daily_claims(id,purchase_id,user_id,day_number,eligible_at,amount_paise,ledger_id,claimed_at) VALUES (?,?,'alice',1,?,3001,?,?)",UUID.randomUUID(),p.id(),java.sql.Timestamp.from(p.startDate()),repository.earningLedger("alice",source),java.sql.Timestamp.from(clock.instant()));return null;
        }));assertEquals(0,winning("alice"));assertEquals(0,count("product_daily_claims"));assertEquals(0,count("winning_transactions"));
    }
    @Test void concurrentClaimAndWithdrawalPreserveBothBalances()throws Exception{
        var p=buy("alice",rangeProduct(2,"20","20"));service.claim("alice",p.id());var b=bank();clock.time=Instant.parse("2026-09-24T18:30:00Z");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var claim=pool.submit(()->{gate.await();return service.claim("alice",p.id());});var withdrawal=pool.submit(()->{gate.await();return withdrawals.create("alice",new WithdrawalDtos.Create(b.id(),BigDecimal.TEN,UUID.randomUUID()));});gate.countDown();assertEquals(2000,claim.get(15,TimeUnit.SECONDS).amountPaise());assertEquals(1000,withdrawal.get(15,TimeUnit.SECONDS).amountPaise());assertEquals(3000,winning("alice"));assertEquals(100000,wallet.balance("alice"));}finally{pool.shutdownNow();}
    }

    @Test void dailyEarningCycleAccumulatesFromIstMidnightAndSettlesExactly(){
        clock.time=Instant.parse("2026-09-23T18:30:00Z");var p=buy("alice",rangeProduct(2,"39.32","39.32"));
        var start=p.dailyEarningCycle();assertEquals(LocalDate.parse("2026-09-24"),start.date());assertEquals(clock.instant(),start.startsAt());assertEquals(0,start.accruedPaise());assertEquals(3932,start.finalAmountPaise());
        clock.time=Instant.parse("2026-09-24T06:30:00Z");var half=service.detail("alice",p.id()).purchase();assertEquals(1966,half.dailyEarningCycle().accruedPaise());assertEquals(3932,half.todayProfitPaise());assertEquals(3932,half.claimablePaise());assertEquals(0,winning("alice"));
        assertEquals(half.dailyEarningCycle(),service.detail("alice",p.id()).purchase().dailyEarningCycle());
        clock.time=Instant.parse("2026-09-24T18:29:58Z");long almost=service.detail("alice",p.id()).purchase().dailyEarningCycle().accruedPaise();assertTrue(almost<3932&&almost>=1966);
        clock.time=Instant.parse("2026-09-24T18:29:59Z");var settled=service.detail("alice",p.id()).purchase().dailyEarningCycle();assertEquals(3932,settled.accruedPaise());assertEquals(new BigDecimal("100.00"),settled.progressPercent());
        service.claim("alice",p.id());assertEquals(3932,winning("alice"));clock.time=Instant.parse("2026-09-24T18:30:00Z");var next=service.detail("alice",p.id()).purchase();assertEquals(LocalDate.parse("2026-09-25"),next.dailyEarningCycle().date());assertEquals(0,next.dailyEarningCycle().accruedPaise());assertEquals(3932,next.claimablePaise());
    }
    @Test void completedDaysCountClaimsRatherThanElapsedCalendarDays(){
        var p=buy("alice",rangeProduct(40,"39.32","39.32"));var claimed=service.claim("alice",p.id()).purchase();assertEquals(1,claimed.completedDays());assertEquals(39,claimed.remainingDays());assertEquals(new BigDecimal("2.50"),claimed.progressPercent());
        assertTrue(service.claim("alice",p.id()).alreadyClaimed());assertEquals(1,service.detail("alice",p.id()).purchase().completedDays());
        clock.time=p.endDate();var expired=service.detail("alice",p.id()).purchase();assertEquals(1,expired.completedDays());assertEquals(39,expired.remainingDays());assertEquals("CLOSED",expired.dailyEarningCycle().status());assertEquals(3932,expired.dailyEarningCycle().accruedPaise());assertEquals(3932,winning("alice"));
    }
    @Test void performanceIsDeterministicDynamicAndDoesNotCreditOrChangeMoney(){
        var p=buy("alice",rangeProduct(2,"39.32","39.32"));var first=service.performance("alice",p.id(),5,24);assertEquals(first,service.performance("alice",p.id(),5,24));
        assertFalse(first.candles().isEmpty());assertTrue(first.candles().stream().allMatch(c->c.high()>=Math.max(c.open(),c.close())&&c.low()<=Math.min(c.open(),c.close())));
        clock.time=clock.time.plusSeconds(60);var later=service.performance("alice",p.id(),5,24);assertNotEquals(first.candles().get(first.candles().size()-1).close(),later.candles().get(later.candles().size()-1).close());assertEquals(first.candles().get(0),later.candles().get(0));assertTrue(later.cycle().accruedPaise()>first.cycle().accruedPaise());
        assertEquals(3932,service.detail("alice",p.id()).purchase().claimablePaise());assertEquals(0,winning("alice"));assertEquals(0,count("product_daily_claims"));
        var minute=service.performance("alice",p.id(),1,24).candles();var five=service.performance("alice",p.id(),5,24).candles().get(0);assertEquals(minute.get(0).open(),five.open());assertEquals(minute.get(4).close(),five.close());assertEquals(minute.subList(0,5).stream().mapToDouble(PerformanceCandle::high).max().orElseThrow(),five.high());
        assertThrows(ResponseStatusException.class,()->service.performance("bob",p.id(),5,24));assertThrows(ResponseStatusException.class,()->service.performance("alice",p.id(),0,24));assertThrows(ResponseStatusException.class,()->service.performance("alice",p.id(),5,100));
        assertEquals(3932,service.claim("alice",p.id()).amountPaise());assertEquals(later,service.performance("alice",p.id(),5,24));assertEquals(3932,winning("alice"));
    }
    @Test void claimProgressTracksSuccessfulClaimsNotElapsedTime(){
        var purchase=buy("alice",product(4,"20"));
        assertEquals(0, purchase.progressPercent().compareTo(BigDecimal.ZERO));
        var claimed=service.claim("alice",purchase.id()).purchase();
        assertEquals(0, claimed.progressPercent().compareTo(new BigDecimal("25")));
        clock.time=purchase.startDate().plusSeconds(2*86400);
        var later=service.detail("alice",purchase.id()).purchase();
        assertEquals(0, later.progressPercent().compareTo(new BigDecimal("25")));
        assertEquals(1,later.claimedDays());
        var second=service.claim("alice",purchase.id()).purchase();
        assertEquals(0,second.progressPercent().compareTo(new BigDecimal("50")));
        clock.time=purchase.endDate();
        var ended=service.detail("alice",purchase.id()).purchase();
        assertEquals("COMPLETED",ended.status());
        assertEquals(0,ended.progressPercent().compareTo(new BigDecimal("50")));
    }

    @Test void soldOutProductsStayVisibleButNewPurchasesAreRejected(){
        UUID id=rangeProduct(2,"20","30");var paid=buy("alice",id);jdbc.update("UPDATE public.products SET active=false WHERE id=?",id);var catalog=new CatalogRepository(jdbc);
        assertTrue(catalog.products(true).stream().anyMatch(p->p.id().equals(id)&&p.soldOut()));assertTrue(catalog.product(id,true).orElseThrow().soldOut());
        assertThrows(ResponseStatusException.class,()->buy("bob",id));assertEquals(200000,wallet.balance("bob"));assertEquals(1,count("product_purchases"));assertEquals(409,assertThrows(ResponseStatusException.class,()->buy("alice",id)).getStatusCode().value());
        jdbc.update("UPDATE public.products SET active=true,start_at=?,end_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(3600)),java.sql.Timestamp.from(clock.instant()),id);assertFalse(catalog.product(id,true).orElseThrow().soldOut());assertEquals("PAID",buy("carol",id).paymentStatus());assertEquals(100000,wallet.balance("carol"));
        assertTrue(service.claim("alice",paid.id()).amountPaise()>=2000);
    }


    MachineRewardService rewardService(){var beans=new StaticListableBeanFactory();beans.addBean("rewards",machineRewards);beans.addBean("wallet",new WithdrawalRepository(jdbc));return new MachineRewardService(beans.getBeanProvider(MachineRewardRepository.class),beans.getBeanProvider(WithdrawalRepository.class),new WithdrawalEvents());}
    @Test void referralClaimsAreOwnedConcurrentAndOnePerFriendEvenAcrossProducts()throws Exception{
        buy("alice",product(2,"1"));service.bind("bob",new Bind(repository.code("alice")));var before=service.invitations("alice",new ReferralFilter(null,null,null,null,null,null,null,null,null,null));assertEquals("PENDING",before.invitations().items().get(0).rewardStatus());
        buy("bob",product(2,"1"));var reward=machineRewards.list("alice").get(0);assertThrows(ResponseStatusException.class,()->rewardService().claim("bob",reward.id()));assertEquals("COMPLETED",service.invitations("alice",new ReferralFilter(null,null,null,null,null,null,null,null,null,null)).invitations().items().get(0).rewardStatus());
        var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Callable<UUID>>();for(int i=0;i<8;i++)jobs.add(()->rewardService().claim("alice",reward.id()).reward().ledgerId());Set<UUID> receipts=new HashSet<>();for(var result:pool.invokeAll(jobs))receipts.add(result.get());assertEquals(1,receipts.size());}finally{pool.shutdownNow();}
        buy("bob",product(2,"1"));assertEquals(1,machineRewards.list("alice").size());assertEquals(3000,winning("alice"));assertEquals(0,winning("bob"));assertEquals(1,count("winning_transactions"));assertEquals("CLAIMED",service.invitations("alice",new ReferralFilter(null,null,null,null,null,null,null,null,null,null)).invitations().items().get(0).rewardStatus());
    }
    @Test void referralProviderCaptureCreatesOfferOnceWithoutAutomaticCredit(){
        buy("bob",product(2,"1"));service.bind("alice",new Bind(repository.code("bob")));UUID id=legacyOrder();
        when(payments.payment("pay_Legacy")).thenReturn(new RazorpayGateway.Payment("pay_Legacy","order_Legacy",100000,"INR","captured",true,0));
        service.webhookPayment("order_Legacy","pay_Legacy");service.webhookPayment("order_Legacy","pay_Legacy");assertEquals("PAID",repository.row(id,"alice").paymentStatus());assertEquals(1,machineRewards.list("bob").size());assertEquals(0,winning("bob"));assertEquals(0,winning("alice"));
    }
}
