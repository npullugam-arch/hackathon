package com.iare.hackathon.wallet;

import static com.iare.hackathon.wallet.WalletDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class WalletServiceTests {
    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    WalletRepository repository;
    RazorpayGateway gateway;
    WalletService service;
    final RazorpayProperties properties = new RazorpayProperties("rzp_test_example", "test-secret");

    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        var source = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("app_private").defaultSchema("app_private").createSchemas(true).load().migrate();
        jdbc = new JdbcTemplate(source);
    }
    @AfterAll static void close() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM app_private.wallet_transactions");
        jdbc.update("DELETE FROM app_private.recharge_orders");
        jdbc.update("DELETE FROM public.users");
        for (String uid : new String[]{"alice", "bob"}) jdbc.update("""
                INSERT INTO public.users(firebase_uid, provider, firebase_created_at, last_login_at)
                VALUES (?, 'google.com', 1000, CURRENT_TIMESTAMP)
                """, uid);
        repository = spy(new WalletRepository(jdbc));
        gateway = mock(RazorpayGateway.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("wallet", repository);
        service = new WalletService(beans.getBeanProvider(WalletRepository.class), gateway, properties);
    }
    static String sign(String payload, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    void order(String id, long amount) {
        repository.saveOrder(UUID.randomUUID(), "alice", new RazorpayGateway.RemoteOrder(id, amount, "INR"));
    }
    VerifyPayment request(String order, String payment, String amount) throws Exception {
        return new VerifyPayment(order, payment, sign(order + "|" + payment, properties.keySecret), new BigDecimal(amount));
    }
    void captured(String order, String payment, long amount) {
        when(gateway.fetchPayment(payment)).thenReturn(new RazorpayGateway.Payment(payment, order, amount, "INR", "captured", true, 0));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM app_private." + table, Integer.class); }

    @Test void createsOrderInExactPaiseAndDoesNotCredit() {
        when(gateway.createOrder(eq(12345L), anyString())).thenReturn(new RazorpayGateway.RemoteOrder("order_A", 12345, "INR"));
        var result = service.create("alice", new BigDecimal("123.45"));
        assertEquals(12345, result.amountPaise()); assertEquals("rzp_test_example", result.keyId());
        assertEquals(0, repository.balance("alice")); assertEquals(1, count("recharge_orders"));
        assertEquals(0, count("wallet_transactions"));
    }
    @Test void rejectsInvalidAmountsAndUnknownUsersBeforeProviderCall() {
        for (String value : new String[]{"0", "-1", "0.99", "1.001", "100000.01", "99999999999999999999"})
            assertThrows(WalletException.class, () -> service.create("alice", new BigDecimal(value)));
        assertThrows(WalletException.class, () -> service.create("alice", null));
        assertThrows(WalletException.class, () -> service.create("missing", new BigDecimal("100")));
        verifyNoInteractions(gateway);
    }
    @Test void creditsCapturedPaymentOnceAndReturnsHistoryAndUpdatedBalance() throws Exception {
        order("order_A", 10000); captured("order_A", "pay_A", 10000);
        var request = request("order_A", "pay_A", "100.00");
        assertEquals(10000, service.verify("alice", request).balancePaise());
        assertTrue(service.verify("alice", request).alreadyCredited());
        assertEquals(10000, repository.balance("alice")); assertEquals(0, repository.balance("bob"));
        assertEquals(1, count("wallet_transactions"));
        var transaction = service.wallet("alice").transactions().get(0);
        assertEquals("alice", transaction.userId()); assertEquals("pay_A", transaction.razorpayPaymentId());
        assertEquals("RECHARGE", transaction.transactionType()); assertEquals("CAPTURED", transaction.paymentStatus());
        assertTrue(service.wallet("bob").transactions().isEmpty());
        verify(gateway, times(1)).fetchPayment("pay_A");
    }
    @Test void rejectsOwnershipAmountAndSignatureTamperingWithoutCredit() throws Exception {
        order("order_A", 10000);
        var valid = request("order_A", "pay_A", "100");
        assertThrows(WalletException.class, () -> service.verify("bob", valid));
        assertThrows(WalletException.class, () -> service.verify("alice", request("order_A", "pay_A", "200")));
        assertThrows(WalletException.class, () -> service.verify("alice", new VerifyPayment("order_A", "pay_A", "0".repeat(64), new BigDecimal("100"))));
        assertEquals(0, repository.balance("alice")); assertEquals(0, count("wallet_transactions")); verifyNoInteractions(gateway);
    }
    @Test void rejectsUncapturedFailedRefundedAndMismatchedProviderPayments() throws Exception {
        order("order_A", 10000);
        for (var payment : new RazorpayGateway.Payment[]{
                new RazorpayGateway.Payment("pay_A", "order_A", 10000, "INR", "authorized", false, 0),
                new RazorpayGateway.Payment("pay_A", "order_A", 10000, "INR", "failed", false, 0),
                new RazorpayGateway.Payment("pay_A", "order_A", 10000, "INR", "captured", true, 100),
                new RazorpayGateway.Payment("pay_A", "order_A", 1, "INR", "captured", true, 0),
                new RazorpayGateway.Payment("pay_A", "order_B", 10000, "INR", "captured", true, 0),
                new RazorpayGateway.Payment("pay_A", "order_A", 10000, "USD", "captured", true, 0),
                new RazorpayGateway.Payment("pay_B", "order_A", 10000, "INR", "captured", true, 0)}) {
            when(gateway.fetchPayment("pay_A")).thenReturn(payment);
            assertThrows(WalletException.class, () -> service.verify("alice", request("order_A", "pay_A", "100")));
        }
        assertEquals(0, repository.balance("alice")); assertEquals(0, count("wallet_transactions"));
    }
    @Test void providerFailureLeavesWalletUntouchedAndRetryWorks() throws Exception {
        order("order_A", 10000);
        when(gateway.fetchPayment("pay_A")).thenThrow(WalletException.unavailable("Provider unavailable"));
        assertThrows(WalletException.class, () -> service.verify("alice", request("order_A", "pay_A", "100")));
        assertEquals(0, repository.balance("alice")); assertEquals(0, count("wallet_transactions"));
        doReturn(new RazorpayGateway.Payment("pay_A", "order_A", 10000, "INR", "captured", true, 0)).when(gateway).fetchPayment("pay_A");
        assertEquals(10000, service.verify("alice", request("order_A", "pay_A", "100")).balancePaise());
    }
    @Test void failureAfterAllWritesRollsBackBalanceTransactionAndOrder() throws Exception {
        order("order_A", 10000); captured("order_A", "pay_A", 10000);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DataIntegrityViolationException("simulated commit-path failure"); })
                .when(repository).credit(any(), anyString());
        assertThrows(DataAccessException.class, () -> service.verify("alice", request("order_A", "pay_A", "100")));
        assertEquals(0, repository.balance("alice")); assertEquals(0, count("wallet_transactions"));
        assertEquals("CREATED", jdbc.queryForObject("SELECT status FROM app_private.recharge_orders WHERE razorpay_order_id='order_A'", String.class));
    }
    @Test void simultaneousCallbacksOnlyCreditOnce() throws Exception {
        order("order_A", 10000); captured("order_A", "pay_A", 10000);
        var request = request("order_A", "pay_A", "100");
        var executor = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> { service.verify("alice", request); return null; });
            }
            for (var future : executor.invokeAll(tasks)) future.get();
        } finally { executor.shutdownNow(); }
        assertEquals(10000, repository.balance("alice")); assertEquals(1, count("wallet_transactions"));
    }
    @Test void simultaneousDifferentOrdersDoNotLoseBalanceUpdates() throws Exception {
        order("order_A", 10000); order("order_B", 20000);
        captured("order_A", "pay_A", 10000); captured("order_B", "pay_B", 20000);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.verify("alice", request("order_A", "pay_A", "100")));
            var second = executor.submit(() -> service.verify("alice", request("order_B", "pay_B", "200")));
            first.get(); second.get();
        } finally { executor.shutdownNow(); }
        assertEquals(30000, repository.balance("alice")); assertEquals(2, count("wallet_transactions"));
    }
    @Test void paymentCannotCreditAnotherOrderEvenIfProviderReturnedInconsistentData() throws Exception {
        order("order_A", 10000); order("order_B", 10000); captured("order_A", "pay_A", 10000);
        service.verify("alice", request("order_A", "pay_A", "100"));
        captured("order_B", "pay_A", 10000);
        assertThrows(DataAccessException.class, () -> service.verify("alice", request("order_B", "pay_A", "100")));
        assertEquals(10000, repository.balance("alice")); assertEquals(1, count("wallet_transactions"));
    }
    @Test void userProfileSynchronizationPreservesWalletBalance() throws Exception {
        order("order_A", 10000); captured("order_A", "pay_A", 10000);
        service.verify("alice", request("order_A", "pay_A", "100"));
        new com.iare.hackathon.user.JdbcUserProfileRepository(jdbc).upsert(new com.iare.hackathon.auth.AuthUser(
                "alice", "Updated", "alice@example.com", null, true, 1000, 2000, "google.com"));
        assertEquals(10000, repository.balance("alice"));
    }
}
