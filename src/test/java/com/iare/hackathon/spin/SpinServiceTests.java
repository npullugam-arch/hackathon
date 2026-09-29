package com.iare.hackathon.spin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.iare.hackathon.withdrawal.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.random.RandomGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SpinServiceTests {
    static EmbeddedPostgres postgres;static JdbcTemplate jdbc;
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-09-27T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    MutableClock clock;SpinRepository repository;WithdrawalRepository wallet;SpinService service;SpinPrizes prizes;WithdrawalEvents events;
    @BeforeAll static void database() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();var source=postgres.getPostgresDatabase();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").schemas("app_private").defaultSchema("app_private").createSchemas(true).load().migrate();
        jdbc=new JdbcTemplate(source);
    }
    @AfterAll static void close() throws Exception {if(postgres!=null)postgres.close();}
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE public.users CASCADE");
        for(String user:List.of("alice","bob"))jdbc.update("INSERT INTO public.users(firebase_uid,email,name,provider,firebase_created_at,last_login_at,wallet_balance_paise) VALUES (?,?,?,'google.com',1000,CURRENT_TIMESTAMP,50000)",user,user+"@example.invalid",user);
        clock=new MutableClock();repository=spy(new SpinRepository(jdbc));wallet=new WithdrawalRepository(jdbc);events=spy(new WithdrawalEvents());
        prizes=spy(new SpinPrizes("50,25,12,7,4,2",mock(RandomGenerator.class)));
        var beans=new StaticListableBeanFactory();beans.addBean("spins",repository);beans.addBean("wallet",wallet);
        service=new SpinService(beans.getBeanProvider(SpinRepository.class),beans.getBeanProvider(WithdrawalRepository.class),prizes,clock,events);
    }
    SpinDtos.Request request(){return new SpinDtos.Request(UUID.randomUUID(),clock.instant().atZone(SpinService.IST).toLocalDate());}
    int count(String table){return jdbc.queryForObject("SELECT count(*) FROM app_private."+table,Integer.class);}
    @Test void rewardCreditsExistingWithdrawableLedgerAndKeepsRechargeUntouched() {
        var result=service.spin("alice",request());
        assertFalse(result.alreadySpun());assertEquals(100,result.reward().amountPaise());assertEquals(100,result.state().availableWinningPaise());assertFalse(result.state().eligible());
        assertEquals(1,count("spin_rewards"));assertEquals(1,count("winning_transactions"));
        assertEquals(100,wallet.dashboard("alice",false).availableWinningPaise());
        assertEquals(50000,jdbc.queryForObject("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid='alice'",Long.class));
        assertEquals(result.reward().id(),service.status("alice").todayReward().id());
        assertEquals("50,25,12,7,4,2",jdbc.queryForObject("SELECT reward_weights FROM app_private.spin_rewards",String.class));
    }
    @Test void refreshRepeatedRequestAndNewRequestOnSameDayReturnOneReceipt() {
        var input=request();var first=service.spin("alice",input);
        assertEquals(first.reward(),service.spin("alice",input).reward());
        var second=service.spin("alice",request());assertTrue(second.alreadySpun());assertEquals(first.reward(),second.reward());
        assertEquals(100,service.status("alice").availableWinningPaise());assertEquals(1,count("spin_rewards"));verify(prizes,times(1)).draw();
    }
    @Test void simultaneousTabsAndApiCallsProduceOnlyOneCredit() throws Exception {
        var executor=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            List<Future<SpinDtos.Result>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->{start.await();return service.spin("alice",request());}));
            start.countDown();Set<UUID> ids=new HashSet<>();int created=0;
            for(var future:futures){var result=future.get(20,TimeUnit.SECONDS);ids.add(result.reward().id());if(!result.alreadySpun())created++;}
            assertEquals(1,ids.size());assertEquals(1,created);assertEquals(1,count("winning_transactions"));assertEquals(100,service.status("alice").availableWinningPaise());
        }finally{executor.shutdownNow();}
    }
    @Test void istMidnightResetsAndLostResponseFromYesterdayCannotConsumeToday() {
        clock.now=Instant.parse("2026-09-27T18:29:59.999Z");var old=request();var first=service.spin("alice",old);
        assertEquals(LocalDate.of(2026,9,27),first.reward().spinDay());assertEquals(Instant.parse("2026-09-27T18:30:00Z"),first.state().resetsAt());
        clock.now=Instant.parse("2026-09-27T18:30:00Z");assertTrue(service.status("alice").eligible());
        var replay=service.spin("alice",old);assertEquals(first.reward(),replay.reward());assertTrue(replay.state().eligible());assertEquals(100,replay.state().availableWinningPaise());
        var next=service.spin("alice",request());assertEquals(LocalDate.of(2026,9,28),next.reward().spinDay());assertEquals(200,next.state().availableWinningPaise());assertEquals(2,count("spin_rewards"));
    }
    @Test void staleFutureAndForgedReplayDatesAreRejected() {
        var date=request().spinDay();
        for(var day:List.of(date.minusDays(1),date.plusDays(1)))assertThrows(ResponseStatusException.class,()->service.spin("alice",new SpinDtos.Request(UUID.randomUUID(),day)));
        var input=request();service.spin("alice",input);
        assertThrows(ResponseStatusException.class,()->service.spin("alice",new SpinDtos.Request(input.requestId(),date.plusDays(1))));
        assertThrows(ResponseStatusException.class,()->service.spin("alice",null));assertEquals(1,count("winning_transactions"));
    }
    @Test void receiptFailureRollsBackTheBalanceAndLedger() {
        doThrow(new IllegalStateException("test receipt failure")).when(repository).insert(any(),anyString(),any(),anyLong(),any(),anyString());
        assertThrows(IllegalStateException.class,()->service.spin("alice",request()));
        assertEquals(0,count("winning_transactions"));assertEquals(0,count("spin_rewards"));assertEquals(0,service.status("alice").availableWinningPaise());
        verify(events,never()).changedAfterCommit(anyString());
    }
    @Test void databaseRejectsOrphanCreditsAndMismatchedReceipts() {
        assertThrows(org.springframework.transaction.TransactionException.class,()->wallet.transaction(()->{wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",100,"daily-spin","daily-spin:"+UUID.randomUUID());return null;}));
        assertEquals(0,service.status("alice").availableWinningPaise());
        var id=UUID.randomUUID();assertThrows(DataAccessException.class,()->wallet.transaction(()->{
            wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",100,"daily-spin","daily-spin:"+id);
            repository.insert(id,"alice",UUID.randomUUID(),200,clock.instant(),prizes.configuration());return null;
        }));assertEquals(0,count("winning_transactions"));
    }
    @Test void databaseUniqueDayGuardWorksWithoutServiceAndRollsBackSecondCredit() {
        service.spin("alice",request());var id=UUID.randomUUID();
        assertThrows(DataAccessException.class,()->wallet.transaction(()->{
            wallet.lockUser("alice");wallet.ledger("alice",null,"EARNING",200,"daily-spin","daily-spin:"+id);
            repository.insert(id,"alice",UUID.randomUUID(),200,clock.instant(),prizes.configuration());return null;
        }));assertEquals(100,service.status("alice").availableWinningPaise());assertEquals(1,count("winning_transactions"));
    }
    @Test void rewardRecordsArePermanentAndOwnedByTheSessionUser() {
        var key=request();var a=service.spin("alice",key);var b=service.spin("bob",key);
        assertNotEquals(a.reward().id(),b.reward().id());assertEquals("bob",service.status("bob").recentRewards().get(0).userId());
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM app_private.spin_rewards WHERE id=?",a.reward().id()));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE app_private.spin_rewards SET amount_paise=200 WHERE id=?",a.reward().id()));
        assertEquals(2,count("spin_rewards"));
    }
    @Test void dailyRecordsRemainPermanentBeyondHistoryPreviewLimit() {
        for(int i=0;i<21;i++){service.spin("alice",request());clock.now=clock.now.plusSeconds(86400);}
        var state=service.status("alice");assertTrue(state.eligible());assertEquals(20,state.recentRewards().size());assertEquals(21,count("spin_rewards"));assertEquals(2100,state.availableWinningPaise());
    }
    @Test void allSixPrizesCanBeCreditedThroughTheRealLedger() {
        long total=0;
        for(long amount:List.of(100L,200L,500L,1000L,2000L,3000L)) {
            doReturn(amount).when(prizes).draw();
            var result=service.spin("alice",request());total+=amount;
            assertEquals(amount,result.reward().amountPaise());assertEquals(total,result.state().availableWinningPaise());
            clock.now=clock.now.plusSeconds(86400);
        }
        assertEquals(6,count("spin_rewards"));assertEquals(6,count("winning_transactions"));
    }
}
