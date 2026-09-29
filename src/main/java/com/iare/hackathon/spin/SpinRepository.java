package com.iare.hackathon.spin;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name="app.supabase.enabled",havingValue="true")
public class SpinRepository {
    private final JdbcTemplate jdbc;
    public SpinRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<SpinDtos.Reward> REWARD=(r,n)->new SpinDtos.Reward(
        r.getObject("id",UUID.class),r.getString("user_id"),r.getObject("request_id",UUID.class),
        r.getLong("amount_paise"),r.getObject("spin_day",LocalDate.class),r.getTimestamp("awarded_at").toInstant(),r.getObject("ledger_id",UUID.class));
    public Optional<SpinDtos.Reward> today(String uid, LocalDate day) {
        return jdbc.query("SELECT * FROM app_private.spin_rewards WHERE user_id=? AND spin_day=?",REWARD,uid,day).stream().findFirst();
    }
    public Optional<SpinDtos.Reward> replay(String uid, UUID request) {
        return jdbc.query("SELECT * FROM app_private.spin_rewards WHERE user_id=? AND request_id=?",REWARD,uid,request).stream().findFirst();
    }
    public List<SpinDtos.Reward> recent(String uid) {
        return jdbc.query("SELECT * FROM app_private.spin_rewards WHERE user_id=? ORDER BY awarded_at DESC,id DESC LIMIT 20",REWARD,uid);
    }
    public void insert(UUID id,String uid,UUID request,long amount,Instant now,String weights) {
        int inserted=jdbc.update("""
            INSERT INTO app_private.spin_rewards(id,user_id,request_id,amount_paise,awarded_at,ledger_id,reward_weights)
            SELECT ?,?,?,?,?,id,? FROM app_private.winning_transactions
            WHERE user_id=? AND source_id=? AND kind='EARNING'
            """,id,uid,request,amount,Timestamp.from(now),weights,uid,"daily-spin:"+id);
        if(inserted!=1) throw new IllegalStateException("Spin credit is missing");
    }
    public long balance(String uid) {
        return jdbc.queryForObject("SELECT winning_balance_paise FROM public.users WHERE firebase_uid=?",Long.class,uid);
    }
}
