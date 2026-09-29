package com.iare.hackathon.withdrawal;

import static com.iare.hackathon.withdrawal.WithdrawalDtos.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name="app.supabase.enabled", havingValue="true")
public class WithdrawalRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public WithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public <T> T transaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    public <T> T snapshot(Supplier<T> action) {
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return read.execute(status -> action.get());
    }
    public long lockUser(String uid) {
        return jdbc.query("SELECT winning_balance_paise FROM public.users WHERE firebase_uid=? FOR UPDATE",
                (r,n) -> r.getLong(1), uid).stream().findFirst().orElseThrow(WithdrawalService::notFound);
    }
    private static BankAccount bank(ResultSet r) throws SQLException {
        return new BankAccount(r.getObject("bank_id", UUID.class), r.getString("bank_code"), r.getString("bank_name"),
                r.getString("holder_name"), "•••• " + r.getString("account_last_four"), r.getString("ifsc"),
                r.getString("nickname"), r.getBoolean("active"));
    }
    public List<BankAccount> banks(String uid) {
        return jdbc.query("SELECT b.*, b.id AS bank_id FROM app_private.bank_accounts b WHERE user_id=? AND active ORDER BY created_at,id",
                (r,n) -> bank(r), uid);
    }
    public BankAccount bank(String uid, UUID id) {
        return jdbc.query("SELECT b.*, b.id AS bank_id FROM app_private.bank_accounts b WHERE user_id=? AND id=?",
                (r,n) -> bank(r), uid, id).stream().findFirst().orElseThrow(WithdrawalService::notFound);
    }
    public boolean duplicateBank(String uid, String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.bank_accounts WHERE user_id=? AND account_fingerprint=?)",
                Boolean.class, uid, fingerprint));
    }
    public BankAccount saveBank(String uid, UUID id, BankInput input, String name, String encrypted, String fingerprint) {
        jdbc.update("""
                INSERT INTO app_private.bank_accounts(id,user_id,bank_code,bank_name,holder_name,account_ciphertext,
                    account_fingerprint,account_last_four,ifsc,nickname) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, uid, input.bankCode(), name, input.holderName().trim(), encrypted, fingerprint,
                input.accountNumber().substring(input.accountNumber().length()-4), input.ifsc(), input.nickname().trim());
        return bank(uid, id);
    }
    public void deactivate(String uid, UUID id) {
        bank(uid, id);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.withdrawals WHERE bank_account_id=? AND status='PROCESSING')",
                Boolean.class, id))) throw WithdrawalService.conflict("This bank account has a processing withdrawal.");
        jdbc.update("UPDATE app_private.bank_accounts SET active=false,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", id, uid);
    }
    public String ciphertext(String uid, UUID bankId) {
        return jdbc.queryForObject("SELECT account_ciphertext FROM app_private.bank_accounts WHERE id=? AND user_id=?", String.class, bankId, uid);
    }
    public Dashboard dashboard(String uid, boolean configured) {
        return jdbc.query("""
                SELECT u.winning_balance_paise,u.wallet_balance_paise,
                  COALESCE((SELECT SUM(amount_paise) FROM app_private.wallet_transactions WHERE user_id=u.firebase_uid AND transaction_type='RECHARGE' AND direction='CREDIT'),0) AS recharges,
                  COALESCE((SELECT SUM(amount_paise) FROM app_private.winning_transactions WHERE user_id=u.firebase_uid AND kind='EARNING'),0) AS earnings,
                  COALESCE((SELECT SUM(amount_paise) FROM app_private.withdrawals WHERE user_id=u.firebase_uid AND status='PROCESSING'),0) AS reserved
                FROM public.users u WHERE firebase_uid=?
                """, (r,n) -> new Dashboard(uid, r.getLong("recharges"), r.getLong("earnings"), r.getLong("winning_balance_paise"),
                r.getLong("reserved"), configured, banks(uid),r.getLong("wallet_balance_paise")), uid).stream().findFirst().orElseThrow(WithdrawalService::notFound);
    }
    private static Instant instant(ResultSet r, String field) throws SQLException {
        var value = r.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static final String SELECT = """
            SELECT w.*, b.id AS bank_id, b.bank_code,b.bank_name,b.holder_name,b.account_last_four,b.ifsc,b.nickname,b.active,
                u.name AS user_name,u.email,u.phone_number
            FROM app_private.withdrawals w JOIN app_private.bank_accounts b ON b.id=w.bank_account_id
            JOIN public.users u ON u.firebase_uid=w.user_id
            """;
    private static final RowMapper<Withdrawal> WITHDRAWAL = (r,n) -> new Withdrawal(r.getObject("id",UUID.class),
            r.getString("user_id"),r.getString("user_name"),r.getString("email"),r.getString("phone_number"),r.getLong("amount_paise"),
            bank(r),r.getString("status"),r.getString("failure_kind"),r.getString("failure_reason"),r.getString("admin_remark"),
            r.getString("reference_id"),instant(r,"requested_at"),instant(r,"processed_at"),instant(r,"completed_at"),
            instant(r,"rejected_at"),instant(r,"updated_at"));
    public Withdrawal detail(UUID id, String uid) {
        var args = new ArrayList<Object>(); args.add(id); if (uid != null) args.add(uid);
        return jdbc.query(SELECT + " WHERE w.id=?" + (uid == null ? "" : " AND w.user_id=?"), WITHDRAWAL, args.toArray())
                .stream().findFirst().orElseThrow(WithdrawalService::notFound);
    }
    public void lockWithdrawal(UUID id) {
        jdbc.queryForObject("SELECT id FROM app_private.withdrawals WHERE id=? FOR UPDATE", UUID.class, id);
    }
    public Optional<Withdrawal> replay(String uid, UUID key) {
        return jdbc.query(SELECT + " WHERE w.user_id=? AND w.idempotency_key=?", WITHDRAWAL, uid, key).stream().findFirst();
    }
    public void configureMinimum(long minimum){jdbc.queryForObject("SELECT set_config('app.withdrawal_minimum_paise',?,true)",String.class,Long.toString(minimum));}
    public void create(UUID id, String uid, Create request, long paise) {
        jdbc.update("INSERT INTO app_private.withdrawals(id,user_id,bank_account_id,amount_paise,idempotency_key) VALUES (?,?,?,?,?)",
                id,uid,request.bankAccountId(),paise,request.idempotencyKey());
        ledger(uid,id,"RESERVE",paise,uid,null);
    }
    public void ledger(String uid, UUID withdrawal, String kind, long paise, String actor, String source) {
        jdbc.update("INSERT INTO app_private.winning_transactions(id,user_id,withdrawal_id,kind,amount_paise,actor,source_id) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),uid,withdrawal,kind,paise,actor,source);
    }
    public Optional<Long> earning(String uid, String source) {
        return jdbc.query("SELECT amount_paise FROM app_private.winning_transactions WHERE user_id=? AND source_id=? AND kind='EARNING'",
                (r,n) -> r.getLong(1), uid,source).stream().findFirst();
    }
    public UUID earningLedger(String uid,String source) {
        return jdbc.query("SELECT id FROM app_private.winning_transactions WHERE user_id=? AND source_id=? AND kind='EARNING'",
                (r,n) -> r.getObject(1,UUID.class),uid,source).stream().findFirst().orElseThrow(WithdrawalService::notFound);
    }
    public void audit(UUID id, String actor, String action, String remark) {
        jdbc.update("INSERT INTO app_private.withdrawal_audit(id,withdrawal_id,actor,action,remark) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),id,actor,action,remark);
    }
    public void update(Withdrawal w, Action action, String actor) {
        boolean completed = action.status().equals("SUCCESSFUL"), refund = Set.of("FAILED","REJECTED").contains(action.status());
        jdbc.update("""
                UPDATE app_private.withdrawals SET status=?,failure_kind=?,failure_reason=?,admin_remark=?,reference_id=?,processed_by=?,
                    processed_at=CURRENT_TIMESTAMP,completed_at=?,rejected_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, completed ? "SUCCESSFUL" : refund ? "REFUNDED" : "PROCESSING", refund ? action.status() : null,
                refund ? action.failureReason() : null, action.adminRemark(), completed ? action.referenceId() : null, actor,
                completed ? Timestamp.from(Instant.now()) : null, refund ? Timestamp.from(Instant.now()) : null, w.id());
        if (completed || refund) ledger(w.userId(),w.id(),completed ? "COMPLETE" : "REFUND",w.amountPaise(),actor,null);
        audit(w.id(),actor,action.status(),refund ? action.failureReason() : action.adminRemark());
    }
    public Page list(String owner, Filter filter) {
        var where = new StringBuilder(" WHERE 1=1"); var args = new ArrayList<Object>();
        if (owner != null) { where.append(" AND w.user_id=?"); args.add(owner); }
        if (filter.status() != null) {
            where.append(Set.of("FAILED","ERROR","REJECTED","CANCELLED").contains(filter.status()) ? " AND w.failure_kind=?" : " AND w.status=?"); args.add(filter.status().equals("REFUNDED") ? "REFUNDED" : WithdrawalService.normalizeStatus(filter.status()));
        }
        if (filter.from() != null) { where.append(" AND w.requested_at>=?"); args.add(Timestamp.from(filter.from().atStartOfDay(ZoneOffset.UTC).toInstant())); }
        if (filter.to() != null) { where.append(" AND w.requested_at<?"); args.add(Timestamp.from(filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())); }
        if (filter.user() != null && !filter.user().isBlank()) {
            where.append(" AND (position(lower(?) in lower(w.user_id))>0 OR position(lower(?) in lower(COALESCE(u.email,'')))>0 OR position(lower(?) in lower(COALESCE(u.name,'')))>0 OR position(? in COALESCE(u.phone_number,''))>0)");
            for (int i=0;i<4;i++) args.add(filter.user().trim());
        }
        if (filter.withdrawalId() != null) { where.append(" AND w.id=?"); args.add(filter.withdrawalId()); }
        if(filter.minAmount()!=null){where.append(" AND w.amount_paise>=?");args.add(filter.minAmount().movePointRight(2).longValueExact());}
        if(filter.maxAmount()!=null){where.append(" AND w.amount_paise<=?");args.add(filter.maxAmount().movePointRight(2).longValueExact());}
        Summary summary=jdbc.queryForObject("SELECT count(*) FILTER(WHERE w.status='PROCESSING') AS processing,count(*) FILTER(WHERE w.status='SUCCESSFUL') AS completed,count(*) FILTER(WHERE w.status='REFUNDED') AS refunded,COALESCE(sum(w.amount_paise),0) AS requested FROM app_private.withdrawals w JOIN public.users u ON u.firebase_uid=w.user_id"+where,
                (r,n)->new Summary(r.getLong("processing"),r.getLong("completed"),r.getLong("refunded"),r.getLong("requested")),args.toArray());
        long count = jdbc.queryForObject("SELECT count(*) FROM app_private.withdrawals w JOIN public.users u ON u.firebase_uid=w.user_id" + where,Long.class,args.toArray());
        args.add(filter.pageSize()); args.add((long)filter.pageNumber()*filter.pageSize());
        var items = jdbc.query(SELECT + where + " ORDER BY w.requested_at DESC,w.id DESC LIMIT ? OFFSET ?", WITHDRAWAL,args.toArray());
        return new Page(items,filter.pageNumber(),filter.pageSize(),count,(count+filter.pageSize()-1)/filter.pageSize(),summary);
    }
}
