package com.iare.hackathon.wallet;

import static com.iare.hackathon.wallet.WalletDtos.*;
import com.iare.hackathon.admin.AdminRechargeDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@Repository
@ConditionalOnProperty(name = "app.supabase.enabled", havingValue = "true")
public class WalletRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    public WalletRepository(JdbcTemplate supabaseJdbcTemplate) {
        jdbc = supabaseJdbcTemplate;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        transactions.setTimeout(30);
        snapshots = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        snapshots.setReadOnly(true);
        snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots.setTimeout(15);
    }
    public <T> T atomic(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    public long balance(String uid) {
        var balances = jdbc.query("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid = ?",
                (rs, row) -> rs.getLong(1), uid);
        if (balances.isEmpty()) throw new WalletException(HttpStatus.UNAUTHORIZED, "Please sign in again to load your account.");
        return balances.get(0);
    }
    public void saveOrder(UUID id, String uid, RazorpayGateway.RemoteOrder order) {
        jdbc.update("INSERT INTO app_private.recharge_orders (id, user_id, razorpay_order_id, amount_paise, currency) VALUES (?, ?, ?, ?, ?)",
                id, uid, order.id(), order.amount(), order.currency());
    }
    public Order lockOrder(String orderId) {
        return jdbc.query("SELECT * FROM app_private.recharge_orders WHERE razorpay_order_id = ? FOR UPDATE",
                (rs, row) -> new Order(rs.getObject("id", UUID.class), rs.getString("user_id"),
                        rs.getString("razorpay_order_id"), rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("status")), orderId)
                .stream().findFirst().orElseThrow(() -> new WalletException(HttpStatus.NOT_FOUND, "Recharge order not found."));
    }
    public String creditedPayment(String orderId) {
        return jdbc.queryForObject("SELECT razorpay_payment_id FROM app_private.wallet_transactions WHERE razorpay_order_id = ?", String.class, orderId);
    }
    public void recordPaymentStatus(Order order, RazorpayGateway.Payment payment) {
        if (payment.status() == null || !java.util.Set.of("created", "authorized", "captured", "failed", "refunded").contains(payment.status()))
            throw WalletException.unavailable("Payment status is unavailable. Please retry verification.");
        jdbc.update("""
                UPDATE app_private.recharge_orders
                SET status = ?, last_payment_id = ?, provider_status = ?, payment_created_at = ?, status_checked_at = CURRENT_TIMESTAMP
                WHERE razorpay_order_id = ? AND status <> 'CREDITED' AND payment_created_at <= ?
                  AND (last_payment_id IS NULL OR last_payment_id = ? OR payment_created_at < ?
                       OR COALESCE(provider_status, '') NOT IN ('authorized', 'captured'))
                """, "failed".equals(payment.status()) ? "FAILED" : "CREATED", payment.id(), payment.status(), payment.created_at(),
                order.orderId(), payment.created_at(), payment.id(), payment.created_at());
    }
    public long credit(Order order, String paymentId) {
        // Both unique constraints are a final defence against duplicate credits; any failure rolls back ALL writes.
        jdbc.update("""
                INSERT INTO app_private.wallet_transactions
                    (id, user_id, razorpay_order_id, razorpay_payment_id, amount_paise, currency, payment_status, transaction_type, direction)
                VALUES (?, ?, ?, ?, ?, ?, 'CAPTURED', 'RECHARGE', 'CREDIT')
                """, UUID.randomUUID(), order.userId(), order.orderId(), paymentId, order.amountPaise(), order.currency());
        long balance = jdbc.queryForObject("""
                UPDATE public.users SET wallet_balance_paise = wallet_balance_paise + ?, updated_at = CURRENT_TIMESTAMP
                WHERE firebase_uid = ? RETURNING wallet_balance_paise
                """, Long.class, order.amountPaise(), order.userId());
        jdbc.update("UPDATE app_private.recharge_orders SET status = 'CREDITED' WHERE razorpay_order_id = ?", order.orderId());
        return balance;
    }
    public long debitForPurchase(String uid, UUID purchaseId, long amountPaise) {
        jdbc.update("""
                INSERT INTO app_private.wallet_transactions
                    (id, user_id, purchase_id, amount_paise, currency, payment_status, transaction_type, direction)
                VALUES (?, ?, ?, ?, 'INR', 'CAPTURED', 'PURCHASE', 'DEBIT')
                """, UUID.randomUUID(), uid, purchaseId, amountPaise);
        try {
            return jdbc.queryForObject("""
                    UPDATE public.users SET wallet_balance_paise = wallet_balance_paise - ?, updated_at = CURRENT_TIMESTAMP
                    WHERE firebase_uid = ? AND wallet_balance_paise >= ? RETURNING wallet_balance_paise
                    """, Long.class, amountPaise, uid, amountPaise);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            long current = jdbc.queryForObject("SELECT wallet_balance_paise FROM public.users WHERE firebase_uid = ?", Long.class, uid);
            String shortfall = java.math.BigDecimal.valueOf(amountPaise - current, 2).toPlainString();
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.CONFLICT, "Insufficient Recharge Balance. Please recharge ₹" + shortfall + " more.");
        }
    }
    public List<Transaction> history(String uid) {
        return jdbc.query("SELECT * FROM app_private.wallet_transactions WHERE user_id = ? AND transaction_type='RECHARGE' AND direction='CREDIT' ORDER BY created_at DESC LIMIT 50",
                (rs, row) -> new Transaction(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getString("razorpay_order_id"),
                        rs.getString("razorpay_payment_id"), rs.getLong("amount_paise"), rs.getString("currency"),
                        rs.getString("payment_status"), rs.getString("transaction_type"), rs.getString("direction"),
                        rs.getTimestamp("created_at").toInstant()), uid);
    }

    // One row per existing recharge order. A verified wallet credit is the sole source of success.
    private static final String ADMIN_ROWS = """
            SELECT o.id AS recharge_id, COALESCE(t.id, o.id) AS transaction_id, t.id AS wallet_transaction_id,
                   o.user_id, u.name AS user_name, u.email AS user_email, u.phone_number AS user_phone,
                   o.amount_paise, o.currency, o.razorpay_order_id,
                   COALESCE(t.razorpay_payment_id, o.last_payment_id) AS razorpay_payment_id,
                   CASE WHEN t.id IS NOT NULL THEN 'SUCCESSFUL' WHEN o.status = 'FAILED' THEN 'FAILED' ELSE 'PENDING' END AS status,
                   CASE WHEN t.id IS NOT NULL THEN 'captured' ELSE o.provider_status END AS provider_status,
                   'RECHARGE' AS transaction_type, COALESCE(t.amount_paise, 0) AS wallet_credited_paise,
                   o.created_at, t.created_at AS credited_at, o.status_checked_at
            FROM app_private.recharge_orders o JOIN public.users u ON u.firebase_uid = o.user_id
            LEFT JOIN app_private.wallet_transactions t ON t.razorpay_order_id = o.razorpay_order_id
                AND t.user_id = o.user_id AND t.amount_paise = o.amount_paise AND t.currency = o.currency
                AND t.payment_status = 'CAPTURED' AND t.transaction_type = 'RECHARGE' AND t.direction = 'CREDIT'
            """;
    private static final RowMapper<AdminRechargeDtos.Recharge> ADMIN_MAPPER = (rs, row) -> new AdminRechargeDtos.Recharge(
            rs.getObject("recharge_id", UUID.class), rs.getObject("transaction_id", UUID.class), rs.getObject("wallet_transaction_id", UUID.class),
            rs.getString("user_id"), rs.getString("user_name"), rs.getString("user_email"), rs.getString("user_phone"),
            rs.getLong("amount_paise"), rs.getString("currency"), rs.getString("razorpay_order_id"), rs.getString("razorpay_payment_id"),
            rs.getString("status"), rs.getString("provider_status"), rs.getString("transaction_type"), rs.getLong("wallet_credited_paise"),
            rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("credited_at")), instant(rs.getTimestamp("status_checked_at")));
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    public Optional<AdminRechargeDtos.Recharge> adminRecharge(UUID id) {
        return jdbc.query("SELECT * FROM (" + ADMIN_ROWS + ") r WHERE recharge_id = ? OR transaction_id = ?", ADMIN_MAPPER, id, id)
                .stream().findFirst();
    }
    public AdminRechargeDtos.Page adminRecharges(AdminRechargeDtos.Filter filter) {
        return snapshots.execute(tx -> {
            var where = new StringBuilder(" WHERE 1=1");
            var parameters = new ArrayList<Object>();
            if (filter.status() != null) { where.append(" AND status = ?"); parameters.add(filter.status()); }
            like(where, parameters, "user_name", filter.name());
            like(where, parameters, "user_email", filter.email());
            like(where, parameters, "user_phone", filter.phone());
            if (filter.transactionId() != null) {
                where.append(" AND (CAST(transaction_id AS TEXT) ILIKE ? ESCAPE '!' OR CAST(recharge_id AS TEXT) ILIKE ? ESCAPE '!')");
                parameters.add(pattern(filter.transactionId())); parameters.add(pattern(filter.transactionId()));
            }
            like(where, parameters, "razorpay_payment_id", filter.paymentId());
            if (filter.from() != null) { where.append(" AND created_at >= ?"); parameters.add(Timestamp.from(filter.from())); }
            if (filter.to() != null) { where.append(" AND created_at < ?"); parameters.add(Timestamp.from(filter.to())); }
            if (filter.search() != null) {
                where.append(" AND (");
                String[] columns = {"user_name", "user_email", "user_phone", "user_id", "CAST(transaction_id AS TEXT)",
                        "CAST(recharge_id AS TEXT)", "razorpay_order_id", "razorpay_payment_id"};
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) where.append(" OR ");
                    where.append(columns[i]).append(" ILIKE ? ESCAPE '!'"); parameters.add(pattern(filter.search()));
                }
                where.append(")");
            }
            String source = " FROM (" + ADMIN_ROWS + ") r" + where;
            var summary = jdbc.queryForObject("""
                    SELECT COUNT(*) AS attempts,
                        COUNT(*) FILTER (WHERE status = 'SUCCESSFUL') AS successful,
                        COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                        COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                        COALESCE(SUM(amount_paise) FILTER (WHERE status = 'SUCCESSFUL'), 0) AS processed,
                        COALESCE(SUM(wallet_credited_paise) FILTER (WHERE status = 'SUCCESSFUL'), 0) AS credited
                    """ + source, (rs, row) -> new AdminRechargeDtos.Summary(rs.getLong("attempts"), rs.getLong("successful"),
                    rs.getLong("failed"), rs.getLong("pending"), rs.getBigDecimal("processed"), rs.getBigDecimal("credited"), "INR"), parameters.toArray());
            long totalPages = (summary.totalAttempts() + filter.size() - 1) / filter.size();
            int page = (int) Math.min(filter.page(), Math.max(0, totalPages - 1));
            parameters.add(filter.size()); parameters.add((long) page * filter.size());
            var items = jdbc.query("SELECT *" + source + " ORDER BY created_at DESC, recharge_id DESC LIMIT ? OFFSET ?",
                    ADMIN_MAPPER, parameters.toArray());
            return new AdminRechargeDtos.Page(items, summary, summary.totalAttempts(), page, filter.size(), totalPages, Instant.now());
        });
    }
    private static void like(StringBuilder where, List<Object> values, String column, String value) {
        if (value != null) { where.append(" AND ").append(column).append(" ILIKE ? ESCAPE '!'"); values.add(pattern(value)); }
    }
    private static String pattern(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
}
