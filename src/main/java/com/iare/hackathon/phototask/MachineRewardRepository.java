package com.iare.hackathon.phototask;

import static com.iare.hackathon.phototask.MachineRewardDtos.*;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
@ConditionalOnProperty(name="app.supabase.enabled",havingValue="true")
public class MachineRewardRepository {
    private final JdbcTemplate jdbc;
    public MachineRewardRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String SELECT="SELECT id,user_id,reward_type,source_id,amount_paise,CASE WHEN status='PENDING' THEN 'COMPLETED' ELSE status END AS status,ledger_id,created_at,claimed_at FROM app_private.machine_reward_claims";
    private static final org.springframework.jdbc.core.RowMapper<Reward> MAP=(r,n)->new Reward(r.getObject("id",UUID.class),r.getString("reward_type"),r.getObject("source_id",UUID.class),r.getLong("amount_paise"),r.getString("status"),r.getObject("ledger_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("claimed_at")==null?null:r.getTimestamp("claimed_at").toInstant());
    public boolean hasPaidPurchase(String uid){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=? AND payment_status='PAID')",Boolean.class,uid));}
    public void createPhoto(UUID task,String uid){jdbc.update("INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise) VALUES (?,?,'TAKE_PHOTO',?,2000) ON CONFLICT(user_id,reward_type,source_id) DO NOTHING",UUID.randomUUID(),uid,task);}
    public void createReferral(UUID referral,String inviter){jdbc.update("INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise) VALUES (?,?,'REFERRAL',?,3000) ON CONFLICT(user_id,reward_type,source_id) DO NOTHING",UUID.randomUUID(),inviter,referral);}
    public List<Reward> list(String uid,String type){return jdbc.query(SELECT+" WHERE user_id=? AND reward_type=? AND status<>'SUPERSEDED' ORDER BY created_at DESC,id DESC",MAP,uid,type);}
    public List<Reward> list(String uid){return jdbc.query(SELECT+" WHERE user_id=? AND status<>'SUPERSEDED' ORDER BY created_at DESC,id DESC",MAP,uid);}
    public long total(String uid){return jdbc.queryForObject("SELECT COALESCE(sum(amount_paise),0) FROM app_private.machine_reward_claims WHERE user_id=? AND reward_type='REFERRAL' AND status='CLAIMED'",Long.class,uid);}
    public Reward lock(UUID id,String uid){return jdbc.query(SELECT+" WHERE id=? AND user_id=? FOR UPDATE",MAP,id,uid).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"This reward is not available."));}
    public Reward claim(Reward reward,String uid,UUID ledger){jdbc.update("UPDATE app_private.machine_reward_claims SET status='CLAIMED',ledger_id=?,claimed_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",ledger,reward.id(),uid);return lock(reward.id(),uid);}
}