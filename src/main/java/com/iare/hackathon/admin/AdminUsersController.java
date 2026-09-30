package com.iare.hackathon.admin;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Read-only admin user overview. Existing admin security protects this API. */
@RestController
@RequestMapping("/api/admin/users")
@ConditionalOnProperty(name="app.supabase.enabled", havingValue="true")
public class AdminUsersController {
    private final JdbcTemplate jdbc;
    public AdminUsersController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping
    public List<Map<String,Object>> users(@RequestParam(defaultValue="") String search){
        String q="""
          SELECT u.firebase_uid AS id,u.name,u.email,u.phone_number,u.created_at,u.last_login_at,
          u.wallet_balance_paise,u.winning_balance_paise,
          (SELECT COALESCE(sum(t.amount_paise),0) FROM app_private.wallet_transactions t WHERE t.user_id=u.firebase_uid AND t.transaction_type='RECHARGE' AND t.direction='CREDIT') AS recharge_total,
          (SELECT count(*) FROM app_private.product_purchases p WHERE p.user_id=u.firebase_uid) AS purchase_count,
          (SELECT count(*) FROM app_private.referrals r WHERE r.inviter_id=u.firebase_uid) AS referral_count
          FROM public.users u WHERE (?='' OR u.firebase_uid ILIKE ? OR COALESCE(u.name,'') ILIKE ? OR COALESCE(u.email,'') ILIKE ? OR COALESCE(u.phone_number,'') ILIKE ?)
          ORDER BY u.created_at DESC""";
        String s="%"+search.trim()+"%";
        return jdbc.queryForList(q,search.trim(),s,s,s,s);
    }
    @GetMapping("/{id}")
    public Map<String,Object> detail(@PathVariable String id){
        var user=jdbc.queryForMap("SELECT firebase_uid AS id,name,email,phone_number,photo_url,email_verified,provider,created_at,last_login_at,wallet_balance_paise,winning_balance_paise FROM public.users WHERE firebase_uid=?",id);
        user.put("recharges",jdbc.queryForList("SELECT id,amount_paise,payment_status,created_at FROM app_private.wallet_transactions WHERE user_id=? AND transaction_type='RECHARGE' ORDER BY created_at DESC",id));
        user.put("withdrawals",jdbc.queryForList("SELECT id,amount_paise,status,requested_at,processed_at FROM app_private.withdrawals WHERE user_id=? ORDER BY requested_at DESC",id));
        user.put("purchases",jdbc.queryForList("SELECT id,product_title,purchase_amount_paise,daily_income_paise,payment_status,created_at,paid_at,end_at,(SELECT count(*) FROM app_private.product_daily_claims c WHERE c.purchase_id=p.id) AS claimed_days FROM app_private.product_purchases p WHERE user_id=? ORDER BY created_at DESC",id));
        user.put("referrals",jdbc.queryForList("SELECT r.id,r.referral_code,r.created_at,u.firebase_uid AS invitee_id,u.name AS invitee_name,u.email AS invitee_email FROM app_private.referrals r JOIN public.users u ON u.firebase_uid=r.invitee_id WHERE r.inviter_id=? ORDER BY r.created_at DESC",id));
        user.put("transactions",jdbc.queryForList("SELECT id,transaction_type,direction,amount_paise,created_at FROM app_private.wallet_transactions WHERE user_id=? ORDER BY created_at DESC LIMIT 100",id));
        return user;
    }
}
