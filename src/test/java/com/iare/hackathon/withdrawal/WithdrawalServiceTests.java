package com.iare.hackathon.withdrawal;

import static com.iare.hackathon.withdrawal.WithdrawalDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class WithdrawalServiceTests {
    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static ValidatorFactory validators;
    WithdrawalRepository repository;
    WithdrawalService service;
    BankEncryption encryption;
    BankAccount bank;
    @BeforeAll static void database() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();
        var source=postgres.getPostgresDatabase();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").schemas("app_private").defaultSchema("app_private").createSchemas(true).load().migrate();
        jdbc=new JdbcTemplate(source);validators=Validation.buildDefaultValidatorFactory();
    }
    @AfterAll static void close() throws Exception { if(validators!=null)validators.close();if(postgres!=null)postgres.close(); }
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE app_private.withdrawal_audit,app_private.winning_transactions,app_private.withdrawals,app_private.bank_accounts,app_private.wallet_transactions,app_private.recharge_orders,public.users CASCADE");
        for(String uid:List.of("alice","bob"))jdbc.update("INSERT INTO public.users(firebase_uid,email,name,provider,firebase_created_at,last_login_at,wallet_balance_paise) VALUES (?,?,?,'google.com',1000,CURRENT_TIMESTAMP,100000)",uid,uid+"@example.invalid",uid);
        repository=spy(new WithdrawalRepository(jdbc));
        encryption=new BankEncryption(Base64.getEncoder().encodeToString(SecureRandom.getSeed(32)));
        var beans=new StaticListableBeanFactory();beans.addBean("withdrawals",repository);
        service=new WithdrawalService(beans.getBeanProvider(WithdrawalRepository.class),new BankDirectory(),encryption,validators.getValidator());
        bank=service.addBank("alice",bankInput("12345678901"));
    }
    static BankInput bankInput(String account){return new BankInput("SBIN","Test Holder",account,account,"SBIN0000001","Main account");}
    void earn(String amount){service.creditEligibleEarning("alice","eligible-event-1",new BigDecimal(amount));}
    Create request(String amount){return new Create(bank.id(),new BigDecimal(amount),UUID.randomUUID());}
    long balance(){return service.dashboard("alice").availableWinningPaise();}
    int count(String table){return jdbc.queryForObject("SELECT count(*) FROM app_private."+table,Integer.class);}
    Action action(String status){return new Action(status,status.equals("SUCCESSFUL")?"BANK-REF-001":null,"Reviewed",Set.of("FAILED","REJECTED").contains(status)?"Transfer did not succeed":null);}

    @Test void zeroWinningCashCannotWithdrawRechargePrincipal(){
        assertEquals(0,balance());assertThrows(ResponseStatusException.class,()->service.create("alice",request("100")));
        assertEquals(100000,jdbc.queryForObject("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid='alice'",Long.class));
        assertEquals(0,count("withdrawals"));assertEquals(0,count("winning_transactions"));
    }
    @Test void validatesMinimumScaleRangeAndBalance(){
        earn("150");
        for(String amount:List.of("-1","0","0.99","100.001","150.01","99999999999","999999999999999999999"))
            assertThrows(ResponseStatusException.class,()->service.create("alice",request(amount)),amount);
        assertThrows(ResponseStatusException.class,()->service.create("alice",new Create(bank.id(),null,UUID.randomUUID())));
        assertThrows(ResponseStatusException.class,()->service.create("alice",new Create(bank.id(),new BigDecimal("100"),null)));
        assertEquals(15000,balance());assertEquals(0,count("withdrawals"));
    }
    @Test void successfulReservationAndRefreshShowCorrectBalances(){
        earn("2250");var result=service.create("alice",request("100.25"));
        assertEquals("PROCESSING",result.status());assertEquals(10025,result.amountPaise());assertEquals(214975,balance());
        assertEquals(10025,service.dashboard("alice").reservedPaise());assertEquals(225000,service.dashboard("alice").totalWinningPaise());
        assertEquals(result,service.detail("alice",result.id()));
        assertEquals(result,service.list("alice",new Filter(null,null,null,null,null,0,20)).items().get(0));
        assertEquals(2,count("winning_transactions"));
    }
    @Test void duplicateAndLostResponseReplayReserveOnce(){
        earn("200");var input=request("100");var first=service.create("alice",input);
        assertEquals(first,service.create("alice",input));assertEquals(10000,balance());assertEquals(1,count("withdrawals"));
        assertThrows(ResponseStatusException.class,()->service.create("alice",new Create(bank.id(),new BigDecimal("150"),input.idempotencyKey())));
        service.process(first.id(),action("SUCCESSFUL"),"admin");assertEquals("SUCCESSFUL",service.create("alice",input).status());assertEquals(10000,balance());
    }
    @Test void simultaneousRequestsCannotOverdraw() throws Exception {
        earn("150");var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            Callable<Boolean> task=()->{start.await();try{service.create("alice",request("100"));return true;}catch(ResponseStatusException ex){return false;}};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(5000,balance());assertEquals(1,count("withdrawals"));
        }finally{executor.shutdownNow();}
    }
    @Test void concurrentIdenticalRequestsReturnOneWithdrawal() throws Exception {
        earn("200");var input=request("100");var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{Callable<Withdrawal> task=()->{start.await();return service.create("alice",input);};var a=executor.submit(task);var b=executor.submit(task);start.countDown();assertEquals(a.get(15,TimeUnit.SECONDS).id(),b.get(15,TimeUnit.SECONDS).id());assertEquals(1,count("withdrawals"));assertEquals(10000,balance());}finally{executor.shutdownNow();}
    }
    @Test void adminProcessingAndCompletionNeverDeductTwice(){
        earn("200");var w=service.create("alice",request("100"));
        assertNotNull(service.process(w.id(),action("PROCESSING"),"admin").processedAt());
        var completed=service.process(w.id(),action("SUCCESSFUL"),"admin");assertEquals("SUCCESSFUL",completed.status());assertNotNull(completed.completedAt());
        assertEquals(completed,service.process(w.id(),action("SUCCESSFUL"),"admin"));assertEquals(10000,balance());assertEquals(0,service.dashboard("alice").reservedPaise());
        assertEquals(3,count("winning_transactions"));assertThrows(ResponseStatusException.class,()->service.process(w.id(),action("FAILED"),"admin"));
    }
    @Test void failedAndRejectedRefundOnlyTheirReservation(){
        earn("300");var a=service.create("alice",request("100"));var b=service.create("alice",request("150"));assertEquals(5000,balance());
        var failed=service.process(a.id(),action("FAILED"),"admin");assertEquals("REFUNDED",failed.status());assertEquals("FAILED",failed.failureKind());assertEquals(15000,balance());
        assertEquals(failed,service.process(a.id(),action("FAILED"),"admin"));assertEquals(15000,balance());
        assertEquals("REFUNDED",service.process(b.id(),action("REJECTED"),"admin").status());assertEquals(30000,balance());assertEquals(30000,service.dashboard("alice").totalWinningPaise());
        assertEquals(100000,jdbc.queryForObject("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid='alice'",Long.class));
    }
    @Test void concurrentCompleteVersusRefundHasOnlyOneOutcome() throws Exception {
        earn("100");var w=service.create("alice",request("100"));var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            var a=executor.submit(()->{start.await();try{return service.process(w.id(),action("SUCCESSFUL"),"admin").status();}catch(ResponseStatusException ex){return "CONFLICT";}});
            var b=executor.submit(()->{start.await();try{return service.process(w.id(),action("FAILED"),"admin").status();}catch(ResponseStatusException ex){return "CONFLICT";}});
            start.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertTrue(results.contains("CONFLICT"));
            assertEquals(3,count("winning_transactions"));assertEquals(service.detail("alice",w.id()).status().equals("REFUNDED")?10000:0,balance());
        }finally{executor.shutdownNow();}
    }
    @Test void otherUsersCannotReadWithdrawalsOrUseBankAccounts(){
        earn("100");var w=service.create("alice",request("100"));
        assertThrows(ResponseStatusException.class,()->service.detail("bob",w.id()));assertThrows(ResponseStatusException.class,()->service.create("bob",request("100")));
        assertThrows(ResponseStatusException.class,()->service.deactivate("bob",bank.id()));assertTrue(service.banks("bob").isEmpty());
        assertTrue(service.list("bob",new Filter(null,null,null,null,null,null,null)).items().isEmpty());
    }
    @Test void reservationRollsBackIfAuditFails(){
        earn("200");doThrow(new IllegalStateException("test storage failure")).when(repository).audit(any(),eq("alice"),eq("REQUESTED"),isNull());
        assertThrows(IllegalStateException.class,()->service.create("alice",request("100")));assertEquals(20000,balance());assertEquals(0,count("withdrawals"));assertEquals(1,count("winning_transactions"));
    }
    @Test void refundRollsBackIfAuditFails(){
        earn("200");var w=service.create("alice",request("100"));doThrow(new IllegalStateException("test storage failure")).when(repository).audit(eq(w.id()),eq("admin"),eq("FAILED"),anyString());
        assertThrows(IllegalStateException.class,()->service.process(w.id(),action("FAILED"),"admin"));assertEquals(10000,balance());assertEquals("PROCESSING",service.detail("alice",w.id()).status());assertEquals(2,count("winning_transactions"));
    }
    @Test void bankDetailsAreEncryptedMaskedAndRevealIsAudited(){
        assertEquals("•••• 8901",bank.maskedAccountNumber());String cipher=repository.ciphertext("alice",bank.id());assertFalse(cipher.contains("12345678901"));
        assertThrows(ResponseStatusException.class,()->encryption.decrypt(cipher,"bob:"+bank.id()));
        earn("100");var w=service.create("alice",request("100"));assertEquals("12345678901",service.payoutDetails(w.id(),"admin").accountNumber());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_private.withdrawal_audit WHERE action='BANK_DETAILS_VIEWED'",Integer.class));
        service.process(w.id(),action("SUCCESSFUL"),"admin");assertEquals("12345678901",service.payoutDetails(w.id(),"admin").accountNumber());
    }
    @Test void validatesBankInputsAndDuplicateAccounts(){
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",bankInput("12345678901")));
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",bankInput("123")));
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",bankInput("00000000000")));
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",new BankInput("SBIN","Holder","12345678901","12345678902","SBIN0000001","Main")));
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",new BankInput("HDFC","Holder","12345678901","12345678901","SBIN0000001","Main")));
        assertThrows(ResponseStatusException.class,()->service.addBank("alice",new BankInput("ZZZZ","Holder","12345678901","12345678901","ZZZZ0000001","Main")));
        assertEquals(1,count("bank_accounts"));
    }
    @Test void pendingBankCannotBeDeactivatedAndInactiveBankCannotBeUsed(){
        earn("300");var w=service.create("alice",request("100"));assertThrows(ResponseStatusException.class,()->service.deactivate("alice",bank.id()));
        service.process(w.id(),action("FAILED"),"admin");service.deactivate("alice",bank.id());assertTrue(service.banks("alice").isEmpty());
        assertThrows(ResponseStatusException.class,()->service.create("alice",request("100")));assertEquals(bank.maskedAccountNumber(),service.detail("alice",w.id()).bankAccount().maskedAccountNumber());
    }
    @Test void completedTransferReferenceCannotBeUsedTwice(){
        earn("200");var a=service.create("alice",request("100"));var b=service.create("alice",request("100"));service.process(a.id(),action("SUCCESSFUL"),"admin");
        assertThrows(DataAccessException.class,()->service.process(b.id(),action("SUCCESSFUL"),"admin"));assertEquals("PROCESSING",service.detail("alice",b.id()).status());assertEquals(0,balance());
    }
    @Test void adminMustSupplyCompletionReferenceAndFailureReason(){
        earn("100");var w=service.create("alice",request("100"));
        assertThrows(ResponseStatusException.class,()->service.process(w.id(),new Action("SUCCESSFUL",null,null,null),"admin"));
        assertThrows(ResponseStatusException.class,()->service.process(w.id(),new Action("FAILED",null,null," "),"admin"));
        assertThrows(ResponseStatusException.class,()->service.process(w.id(),new Action("REFUNDED",null,null,"bad"),"admin"));
        assertEquals("PROCESSING",service.detail("alice",w.id()).status());
    }
    @Test void earningCreditsAreIdempotentAndLedgerIsImmutable(){
        earn("150");earn("150");assertEquals(15000,balance());assertEquals(1,count("winning_transactions"));
        assertThrows(ResponseStatusException.class,()->earn("200"));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE app_private.winning_transactions SET amount_paise=1"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM app_private.winning_transactions"));assertEquals(15000,balance());
    }
    @Test void ledgerFailureCannotLeaveNegativeBalance(){
        var id=UUID.randomUUID();assertThrows(DataAccessException.class,()->repository.transaction(()->{repository.create(id,"alice",request("100"),10000);return null;}));
        assertEquals(0,balance());assertEquals(0,count("withdrawals"));
    }
    @Test void filtersAndPaginationShowRefundReasonAndOwnership(){
        earn("300");var a=service.create("alice",request("100"));service.create("alice",request("100"));service.process(a.id(),action("FAILED"),"admin");
        var filter=new Filter("FAILED",null,null,"alice",null,0,1);var result=service.list(null,filter);assertEquals(1,result.total());assertEquals(a.id(),result.items().get(0).id());
        assertEquals(2,service.list(null,new Filter(null,null,null,null,null,0,1)).totalPages());
        assertThrows(ResponseStatusException.class,()->service.list(null,new Filter(null,null,null,null,null,-1,20)));
    }
    @Test void missingEncryptionKeyFailsClosedAndMalformedKeysFailStartup(){
        var missing=new BankEncryption("");assertFalse(missing.configured());assertThrows(ResponseStatusException.class,()->missing.encrypt("12345678901","context"));
        assertThrows(IllegalArgumentException.class,()->new BankEncryption("invalid-key"));
    }

    @Test void aliasesRefundExactAmountAndDuplicateProcessingIsNoOp(){
        earn("200.75"); var w=service.create("alice",request("100.25"));
        service.process(w.id(),action("PROCESSING"),"admin"); int audits=count("withdrawal_audit");
        service.process(w.id(),new Action("PROCESSING",null,"Another remark",null),"admin");
        assertEquals(audits,count("withdrawal_audit"));
        var failed=new Action("ERROR",null,null,"Technical issue during payment processing");
        assertEquals("REFUNDED",service.process(w.id(),failed,"admin").status());
        assertEquals(20075,balance());service.process(w.id(),failed,"admin");assertEquals(20075,balance());
        var snapshot=service.snapshot("alice",new Filter(null,null,null,null,null,0,20));
        assertEquals(20075,snapshot.dashboard().availableWinningPaise());
        assertEquals("REFUNDED",snapshot.history().items().get(0).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_private.winning_transactions WHERE kind='REFUND'",Integer.class));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE app_private.withdrawals SET status='PROCESSING',failure_kind=null,failure_reason=null WHERE id=?",w.id()));
    }
    @Test void legacySuccessAndAdminFullDetailsKeepUserBankMasked(){
        earn("200"); var w=service.create("alice",request("100"));
        service.process(w.id(),new Action("COMPLETED","LEGACY-REF",null,null),"admin");
        assertEquals("SUCCESSFUL",service.detail("alice",w.id()).status());assertEquals(10000,balance());
        assertEquals("12345678901",service.adminDetail(w.id(),"admin").accountNumber());
        assertFalse(service.detail("alice",w.id()).bankAccount().maskedAccountNumber().contains("12345678901"));
        assertEquals("12345678901",service.adminList(new Filter(null,null,null,null,null,0,20),"admin").items().get(0).accountNumber());
    }
    @Test void concurrentRefundRetriesCreditOnlyOnce() throws Exception {
        earn("200.75");var w=service.create("alice",request("100.25"));
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Withdrawal> task=()->{start.await();return service.process(w.id(),new Action("CANCELLED",null,null,"Cancelled before bank transfer"),"admin");};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(20075,balance());assertEquals(3,count("winning_transactions"));
        }finally{executor.shutdownNow();}
    }
}
