package com.iare.hackathon.wallet;

import com.iare.hackathon.admin.AdminRechargeDtos;
import com.iare.hackathon.admin.AdminRechargeService;
import com.iare.hackathon.auth.AuthUser;
import com.iare.hackathon.user.JdbcUserProfileRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminRechargeRepositoryTests {
    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    WalletRepository repository;
    WalletService wallet;
    AdminRechargeService admin;
    RazorpayGateway gateway;
    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        Flyway.configure().dataSource(postgres.getPostgresDatabase()).locations("classpath:db/migration")
                .schemas("app_private").defaultSchema("app_private").createSchemas(true).load().migrate();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
    }
    @AfterAll static void close() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM app_private.wallet_transactions"); jdbc.update("DELETE FROM app_private.recharge_orders");
        jdbc.update("DELETE FROM public.users");
        var users = new JdbcUserProfileRepository(jdbc);
        users.upsert(new AuthUser("alice", "Alice One", "alice@example.test", null, true, 1000, 2000, "google.com", "+919876543210"));
        users.upsert(new AuthUser("bob", "Bob Two", "bob@example.test", null, true, 1000, 2000, "google.com"));
        repository = new WalletRepository(jdbc); gateway = mock(RazorpayGateway.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("wallet", repository);
        wallet = new WalletService(beans.getBeanProvider(WalletRepository.class), gateway, new RazorpayProperties("test-key", "test-secret"));
        admin = new AdminRechargeService(beans.getBeanProvider(WalletRepository.class));
    }
    AdminRechargeDtos.Filter all() { return filter(null, null, null, null, null, null, null); }
    AdminRechargeDtos.Filter filter(String status, String name, String email, String phone, String tx, String payment, String search) {
        return new AdminRechargeDtos.Filter(0, 25, status, name, email, phone, tx, payment, search, null, null);
    }
    UUID order(String user, String order, long amount, String date) {
        UUID id = UUID.randomUUID(); repository.saveOrder(id, user, new RazorpayGateway.RemoteOrder(order, amount, "INR"));
        jdbc.update("UPDATE app_private.recharge_orders SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(date)), id);
        return id;
    }
    void payment(String order, String id, long amount, String status, long created) {
        when(gateway.fetchPayment(id)).thenReturn(new RazorpayGateway.Payment(id, order, amount, "INR", status, status.equals("captured"), 0, created));
    }
    WalletDtos.VerifyPayment verification(String order, String payment, long amount) throws Exception {
        return new WalletDtos.VerifyPayment(order, payment, WalletServiceTests.sign(order + "|" + payment, "test-secret"), BigDecimal.valueOf(amount, 2));
    }
    UUID seed() throws Exception {
        var id = order("alice", "order_A", 12345, "2026-09-01T00:00:00Z");
        payment("order_A", "pay_A", 12345, "captured", 100);
        wallet.verify("alice", verification("order_A", "pay_A", 12345));
        order("alice", "order_F", 20000, "2026-09-02T00:00:00Z");
        payment("order_F", "pay_F", 20000, "failed", 200);
        wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_F", "pay_F"));
        order("bob", "order_P", 50000, "2026-09-03T00:00:00Z");
        return id;
    }
    @Test void summariesOnlyCountVerifiedCreditsAndJoinExistingProfiles() throws Exception {
        var id = seed(); var result = admin.list(all()); var summary = result.summary();
        assertEquals(3, summary.totalAttempts()); assertEquals(1, summary.successful());
        assertEquals(1, summary.failed()); assertEquals(1, summary.pending());
        assertEquals(0, new BigDecimal("12345").compareTo(summary.successfulAmountPaise()));
        assertEquals(summary.successfulAmountPaise(), summary.walletCreditedPaise());
        assertEquals(12345, repository.balance("alice")); assertEquals(0, repository.balance("bob"));
        var detail = admin.detail(id); assertEquals("Alice One", detail.userName()); assertEquals("alice@example.test", detail.userEmail());
        assertEquals("+919876543210", detail.userPhone()); assertEquals("SUCCESSFUL", detail.status());
        assertNotNull(detail.walletTransactionId()); assertNotNull(detail.creditedAt()); assertEquals("RECHARGE", detail.transactionType());
        assertEquals(detail, admin.detail(detail.transactionId()));
        assertNull(result.items().get(0).userPhone());
    }
    @Test void everyRequestedFilterAndGlobalSearchWorksWithoutDuplicateRows() throws Exception {
        var id = seed();
        assertEquals(1, admin.list(filter("SUCCESSFUL", null, null, null, null, null, null)).total());
        assertEquals(1, admin.list(filter("FAILED", null, null, null, null, null, null)).total());
        assertEquals(1, admin.list(filter("PENDING", null, null, null, null, null, null)).total());
        assertEquals(2, admin.list(filter(null, "aLiCe", null, null, null, null, null)).total());
        assertEquals(2, admin.list(filter(null, null, "ALICE@", null, null, null, null)).total());
        assertEquals(2, admin.list(filter(null, null, null, "987654", null, null, null)).total());
        assertEquals(1, admin.list(filter(null, null, null, null, id.toString(), null, null)).total());
        assertEquals(1, admin.list(filter(null, null, null, null, admin.detail(id).transactionId().toString(), null, null)).total());
        assertEquals(1, admin.list(filter(null, null, null, null, null, "pay_F", null)).total());
        for (String value : new String[]{"Bob", "bob@example.test", "order_P"})
            assertEquals(1, admin.list(filter(null, null, null, null, null, null, value)).total());
        assertEquals(0, admin.list(filter("SUCCESSFUL", "Bob", null, null, null, null, null)).total());
    }
    @Test void literalWildcardsAndSqlInjectionCannotBroadenSearch() throws Exception {
        seed();
        for (String value : new String[]{"%", "' OR 1=1 --", "!"})
            assertEquals(0, admin.list(filter(null, null, null, null, null, null, value)).total());
        // Provider IDs all contain a literal underscore; a name-only search must not match it as a wildcard.
        assertEquals(3, admin.list(filter(null, null, null, null, null, null, "_")).total());
        assertEquals(0, admin.list(filter(null, "_", null, null, null, null, null)).total());
        jdbc.update("UPDATE public.users SET name = 'Alice 100%_!' WHERE firebase_uid = 'alice'");
        assertEquals(2, admin.list(filter(null, "100%_!", null, null, null, null, null)).total());
    }
    @Test void newestFirstPaginationAndDateBoundariesAreStable() throws Exception {
        seed();
        var first = admin.list(new AdminRechargeDtos.Filter(0, 2, null, null, null, null, null, null, null, null, null));
        assertEquals(2, first.totalPages()); assertEquals("order_P", first.items().get(0).razorpayOrderId());
        assertEquals("order_F", first.items().get(1).razorpayOrderId());
        var last = admin.list(new AdminRechargeDtos.Filter(99, 2, null, null, null, null, null, null, null, null, null));
        assertEquals(1, last.page()); assertEquals(1, last.items().size()); assertEquals("order_A", last.items().get(0).razorpayOrderId());
        var range = admin.list(new AdminRechargeDtos.Filter(0, 25, null, null, null, null, null, null, null,
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z")));
        assertEquals(1, range.total()); assertEquals("FAILED", range.items().get(0).status());
        assertEquals(0, range.summary().successfulAmountPaise().signum());
        assertThrows(ResponseStatusException.class, () -> admin.list(new AdminRechargeDtos.Filter(0, 25, null, null, null, null, null, null, null, Instant.now(), Instant.EPOCH)));
    }
    @Test void capturedObservationWithoutSignatureNeverBecomesSuccessful() {
        var id = order("alice", "order_A", 10000, "2026-09-01T00:00:00Z"); payment("order_A", "pay_A", 10000, "captured", 100);
        wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_A"));
        assertEquals("PENDING", admin.detail(id).status()); assertEquals("captured", admin.detail(id).providerStatus());
        assertEquals(0, admin.list(all()).summary().successful()); assertEquals(0, repository.balance("alice"));
    }
    @Test void confirmedFailureIsStoredButDoesNotCreditAndRetryCanSucceed() throws Exception {
        var id = order("alice", "order_A", 10000, "2026-09-01T00:00:00Z"); payment("order_A", "pay_A", 10000, "failed", 100);
        assertThrows(WalletException.class, () -> wallet.verify("alice", verification("order_A", "pay_A", 10000)));
        assertEquals("FAILED", admin.detail(id).status()); assertEquals(0, repository.balance("alice"));
        payment("order_A", "pay_B", 10000, "captured", 200);
        wallet.verify("alice", verification("order_A", "pay_B", 10000));
        wallet.verify("alice", verification("order_A", "pay_B", 10000));
        wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_A"));
        assertEquals("SUCCESSFUL", admin.detail(id).status()); assertEquals("pay_B", admin.detail(id).razorpayPaymentId());
        assertEquals(1, admin.list(all()).summary().totalAttempts()); assertEquals(10000, repository.balance("alice"));
    }
    @Test void olderFailureCannotReplaceNewerPendingPaymentAndForeignPaymentsAreRejected() {
        var id = order("alice", "order_A", 10000, "2026-09-01T00:00:00Z");
        payment("order_A", "pay_New", 10000, "authorized", 200); payment("order_A", "pay_Old", 10000, "failed", 100);
        wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_New"));
        wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_Old"));
        assertEquals("PENDING", admin.detail(id).status()); assertEquals("pay_New", admin.detail(id).razorpayPaymentId());
        assertThrows(WalletException.class, () -> wallet.observePayment("bob", new WalletDtos.PaymentStatusRequest("order_A", "pay_Old")));
        payment("order_Foreign", "pay_Foreign", 10000, "failed", 300);
        assertThrows(WalletException.class, () -> wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_Foreign")));
        payment("order_A", "pay_Amount", 1, "failed", 300);
        assertThrows(WalletException.class, () -> wallet.observePayment("alice", new WalletDtos.PaymentStatusRequest("order_A", "pay_Amount")));
        assertEquals("PENDING", admin.detail(id).status());
    }
    @Test void profileUpdatesAreReflectedWithoutCopyingUserDataIntoPayments() throws Exception {
        var id = seed();
        var users = new JdbcUserProfileRepository(jdbc);
        users.upsert(new AuthUser("alice", "Alice Updated", "new@example.test", null, true, 1000, 3000, "google.com", "+919999999999"));
        var detail = admin.detail(id); assertEquals("Alice Updated", detail.userName()); assertEquals("new@example.test", detail.userEmail());
        assertEquals("+919999999999", detail.userPhone()); assertEquals(12345, repository.balance("alice"));
        users.upsert(new AuthUser("alice", "Alice Updated", "new@example.test", null, true, 1000, 4000, "google.com"));
        assertEquals("+919999999999", users.findByFirebaseUid("alice").orElseThrow().toAuthUser().phoneNumber());
    }
    @Test void emptySummaryAndMissingDetailsAreHandled() {
        var result = admin.list(all()); assertEquals(0, result.total()); assertTrue(result.items().isEmpty());
        assertEquals(0, result.summary().walletCreditedPaise().signum()); assertEquals(0, result.page());
        assertThrows(ResponseStatusException.class, () -> admin.detail(UUID.randomUUID()));
    }
}
