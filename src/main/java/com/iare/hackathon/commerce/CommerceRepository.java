package com.iare.hackathon.commerce;

import static com.iare.hackathon.commerce.CommerceDtos.*;
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
@ConditionalOnProperty(name="app.supabase.enabled",havingValue="true")
public class CommerceRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public CommerceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));}
    public <T> T transaction(Supplier<T> work){return transactions.execute(s->work.get());}
    public void lockReferralGraph(){jdbc.execute("SELECT pg_advisory_xact_lock(873210904)");}
    public void lockUsers(Collection<String> users){
        for(String uid:new TreeSet<>(users))if(jdbc.query("SELECT firebase_uid FROM public.users WHERE firebase_uid=? FOR UPDATE",(r,n)->r.getString(1),uid).isEmpty())throw CommerceService.notFound();
    }
    public record Terms(UUID id,String title,String image,long price,long daily,int duration,boolean active,long minimum){}
    public Terms terms(UUID id){return jdbc.query("SELECT * FROM public.products WHERE id=? FOR SHARE",(r,n)->new Terms(id,r.getString("title"),r.getString("image_url"),
            r.getBigDecimal("discount_price").movePointRight(2).longValueExact(),r.getBigDecimal("daily_income").movePointRight(2).longValueExact(),r.getInt("duration_days"),
            r.getBoolean("active"),r.getBigDecimal("minimum_daily_income").movePointRight(2).longValueExact()),id).stream().findFirst().orElseThrow(CommerceService::notFound);}
    public record Row(UUID id,String uid,String userName,String email,UUID productId,String title,String image,long price,long daily,int duration,
            Instant configuredStart,String zone,LocalTime time,String orderId,String paymentId,String paymentStatus,Instant paidAt,Instant firstClaim,Instant end,
            Instant created,long claimed,int claimCount,int lastClaimDay,long minimum,List<Long> amounts){}
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    private static final String SELECT="""
            SELECT p.*,u.name AS user_name,u.email,c.claimed,c.claim_count,c.last_claim_day, ARRAY(SELECT amount_paise FROM app_private.product_daily_amounts a WHERE a.purchase_id=p.id ORDER BY day_number) AS amounts
            FROM app_private.product_purchases p JOIN public.users u ON u.firebase_uid=p.user_id
            LEFT JOIN LATERAL (SELECT COALESCE(sum(amount_paise),0) AS claimed,count(*) AS claim_count,COALESCE(max(day_number),0) AS last_claim_day
                FROM app_private.product_daily_claims WHERE purchase_id=p.id) c ON true
            """;
    private static final RowMapper<Row> ROW=(r,n)->new Row(r.getObject("id",UUID.class),r.getString("user_id"),r.getString("user_name"),r.getString("email"),
            r.getObject("product_id",UUID.class),r.getString("product_title"),r.getString("image_url"),r.getLong("purchase_amount_paise"),r.getLong("daily_income_paise"),
            r.getInt("duration_days"),instant(r,"configured_start_at"),r.getString("claim_zone"),r.getTime("claim_time").toLocalTime(),r.getString("razorpay_order_id"),
            r.getString("razorpay_payment_id"),r.getString("payment_status"),instant(r,"paid_at"),instant(r,"first_claim_at"),instant(r,"end_at"),instant(r,"created_at"),
            r.getLong("claimed"),r.getInt("claim_count"),r.getInt("last_claim_day"),r.getLong("minimum_daily_income_paise"),Arrays.asList((Long[])r.getArray("amounts").getArray()));
    public Row row(UUID id,String uid){var args=new ArrayList<Object>();args.add(id);if(uid!=null)args.add(uid);
        return jdbc.query(SELECT+" WHERE p.id=?"+(uid==null?"":" AND p.user_id=?"),ROW,args.toArray()).stream().findFirst().orElseThrow(CommerceService::notFound);}
    public Optional<Row> existing(String uid,UUID product){return jdbc.query(SELECT+" WHERE p.user_id=? AND p.product_id=?",ROW,uid,product).stream().findFirst();}
    public Optional<Row> byOrder(String order){return jdbc.query(SELECT+" WHERE p.razorpay_order_id=?",ROW,order).stream().findFirst();}
    public void lockPurchase(UUID id){jdbc.queryForObject("SELECT id FROM app_private.product_purchases WHERE id=? FOR UPDATE",UUID.class,id);}
    public UUID intent(String uid,Terms t,CommerceConfiguration.ClaimSchedule schedule){
        UUID id=UUID.randomUUID();jdbc.update("""
                INSERT INTO app_private.product_purchases(id,user_id,product_id,product_title,image_url,purchase_amount_paise,daily_income_paise,minimum_daily_income_paise,
                    duration_days,configured_start_at,claim_zone,claim_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,uid,t.id(),t.title(),t.image(),t.price(),t.daily(),t.minimum(),t.duration(),Timestamp.from(Instant.now()),schedule.zone().getId(),Time.valueOf(schedule.time()));
        var random=new java.security.SecureRandom();var values=new ArrayList<Object[]>();
        for(int day=1;day<=t.duration();day++)values.add(new Object[]{id,day,random.nextLong(t.minimum(),t.daily()+1)});
        jdbc.batchUpdate("INSERT INTO app_private.product_daily_amounts(purchase_id,day_number,amount_paise) VALUES (?,?,?)",values);
        return id;
    }
    public void order(UUID id,String order){jdbc.update("UPDATE app_private.product_purchases SET razorpay_order_id=?,payment_status='PENDING',updated_at=CURRENT_TIMESTAMP WHERE id=?",order,id);}
    public void observed(UUID id,boolean failed){jdbc.update("UPDATE app_private.product_purchases SET payment_status=?,last_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND payment_status<>'PAID'",failed?"FAILED":"PENDING",id);}
    public void paid(Row p,String payment,Instant paid,Instant first,Instant end){
        jdbc.update("""
                UPDATE app_private.product_purchases SET payment_status='PAID',razorpay_payment_id=?,paid_at=?,first_claim_at=?,end_at=?,
                    last_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """,payment,Timestamp.from(paid),Timestamp.from(first),Timestamp.from(end),p.id());
        jdbc.update("INSERT INTO app_private.product_payment_transactions(id,purchase_id,razorpay_payment_id,amount_paise) VALUES (?,?,?,?)",UUID.randomUUID(),p.id(),payment,p.price());
    }
    public void paidFromWallet(Row p,Instant paid,Instant first,Instant end){
        jdbc.update("""
                UPDATE app_private.product_purchases SET payment_status='PAID',paid_at=?,first_claim_at=?,end_at=?,
                    last_checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """,Timestamp.from(paid),Timestamp.from(first),Timestamp.from(end),p.id());
    }
    public UUID earningLedger(String uid,String source){return jdbc.queryForObject("SELECT id FROM app_private.winning_transactions WHERE user_id=? AND source_id=? AND kind='EARNING'",UUID.class,uid,source);}
    public Claim claim(Row row,int day,Instant eligible,UUID ledger,Instant now){
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_private.product_daily_claims(id,purchase_id,user_id,day_number,eligible_at,amount_paise,ledger_id,claimed_at) VALUES (?,?,?,?,?,?,?,?)",
                id,row.id(),row.uid(),day,Timestamp.from(eligible),row.amounts().get(day-1),ledger,Timestamp.from(now));return new Claim(id,day,row.amounts().get(day-1),eligible,now,ledger);
    }
    private static final RowMapper<Claim> CLAIM=(r,n)->new Claim(r.getObject("id",UUID.class),r.getInt("day_number"),r.getLong("amount_paise"),instant(r,"eligible_at"),instant(r,"claimed_at"),r.getObject("ledger_id",UUID.class));
    public Optional<Claim> claimed(UUID id,int day){return jdbc.query("SELECT * FROM app_private.product_daily_claims WHERE purchase_id=? AND day_number=?",CLAIM,id,day).stream().findFirst();}
    public List<Claim> claims(UUID id){return jdbc.query("SELECT * FROM app_private.product_daily_claims WHERE purchase_id=? ORDER BY day_number",CLAIM,id);}
    public UUID paymentTransaction(UUID id){return jdbc.query("SELECT id FROM app_private.product_payment_transactions WHERE purchase_id=?",(r,n)->r.getObject(1,UUID.class),id).stream().findFirst().orElse(null);}
    private record Query(String where,List<Object> args){}
    private Query query(String owner,Filter f,Instant now){
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        if(owner!=null){where.append(" AND p.user_id=?");args.add(owner);}
        if(f.productId()!=null){where.append(" AND p.product_id=?");args.add(f.productId());}
        if(f.user()!=null && !f.user().isBlank()){where.append(" AND (position(lower(?) in lower(p.user_id))>0 OR position(lower(?) in lower(COALESCE(u.name,'')))>0 OR position(lower(?) in lower(COALESCE(u.email,'')))>0)");for(int i=0;i<3;i++)args.add(f.user().trim());}
        dates(where,args,"p.created_at",f.from(),f.to());
        if(f.paymentStatus()!=null){where.append(" AND p.payment_status=?");args.add(f.paymentStatus());}
        if(f.status()!=null){
            if(f.status().equals("UNPAID"))where.append(" AND p.payment_status<>'PAID'");
            else{where.append(" AND p.payment_status='PAID' AND "+(f.status().equals("ACTIVE")?"p.end_at>? AND (p.daily_income_paise=0 OR (SELECT count(*) FROM app_private.product_daily_claims d WHERE d.purchase_id=p.id)<p.duration_days)":"(p.end_at<=? OR (p.daily_income_paise>0 AND (SELECT count(*) FROM app_private.product_daily_claims d WHERE d.purchase_id=p.id)>=p.duration_days))"));args.add(Timestamp.from(now));}
        }
        return new Query(where.toString(),args);
    }
    public Page<Row> purchases(String uid,Filter f,Instant now){var q=query(uid,f,now);var args=q.args();
        long total=jdbc.queryForObject("SELECT count(*) FROM app_private.product_purchases p JOIN public.users u ON u.firebase_uid=p.user_id"+q.where(),Long.class,args.toArray());
        String sort=f.sort()==null?"newest":f.sort();String order=switch(sort){case "oldest"->"p.created_at ASC,p.id";case "amount_desc"->"p.purchase_amount_paise DESC,p.id";case "amount_asc"->"p.purchase_amount_paise ASC,p.id";default->"p.created_at DESC,p.id DESC";};
        args.add(f.pageSize());args.add((long)f.pageNumber()*f.pageSize());
        return new Page<>(jdbc.query(SELECT+q.where()+" ORDER BY "+order+" LIMIT ? OFFSET ?",ROW,args.toArray()),f.pageNumber(),f.pageSize(),total,(total+f.pageSize()-1)/f.pageSize());
    }
    public Page<ProductSales> sales(Filter f,Instant now){var q=query(null,f,now);String where=q.where()+" AND p.payment_status='PAID'";
        long total=jdbc.queryForObject("SELECT count(DISTINCT p.product_id) FROM app_private.product_purchases p JOIN public.users u ON u.firebase_uid=p.user_id"+where,Long.class,q.args().toArray());
        var args=new ArrayList<Object>();args.add(Timestamp.from(now));args.add(Timestamp.from(now));args.addAll(q.args());args.add(f.pageSize());args.add((long)f.pageNumber()*f.pageSize());
        String sql="""
                SELECT p.product_id,max(p.product_title) AS title,count(*) AS sold,count(DISTINCT p.user_id) AS purchasers,
                    sum(p.purchase_amount_paise) AS sales,sum(c.claimed) AS claimed,
                    sum(p.daily_income_paise*p.duration_days-c.claimed) AS unclaimed,
                    count(*) FILTER(WHERE p.end_at>? AND (p.daily_income_paise=0 OR c.claim_count<p.duration_days)) AS active,count(*) FILTER(WHERE p.end_at<=? OR (p.daily_income_paise>0 AND c.claim_count>=p.duration_days)) AS completed
                FROM app_private.product_purchases p JOIN public.users u ON u.firebase_uid=p.user_id
                LEFT JOIN LATERAL(SELECT COALESCE(sum(amount_paise),0) AS claimed,count(*) AS claim_count FROM app_private.product_daily_claims WHERE purchase_id=p.id)c ON true
                """+where+" GROUP BY p.product_id ORDER BY max(p.created_at) DESC,p.product_id LIMIT ? OFFSET ?";
        var items=jdbc.query(sql,(r,n)->new ProductSales(r.getObject("product_id",UUID.class),r.getString("title"),r.getLong("sold"),r.getLong("purchasers"),r.getLong("sales"),r.getLong("claimed"),r.getLong("unclaimed"),r.getLong("active"),r.getLong("completed")),args.toArray());
        return new Page<>(items,f.pageNumber(),f.pageSize(),total,(total+f.pageSize()-1)/f.pageSize());
    }
    public record Referral(UUID id,String inviter,String invitee,String code){}
    public record InviterProfile(String userId,String name,String photoUrl){}
    public Optional<Referral> referral(String uid){return jdbc.query("SELECT * FROM app_private.referrals WHERE invitee_id=?",(r,n)->new Referral(r.getObject("id",UUID.class),r.getString("inviter_id"),uid,r.getString("referral_code")),uid).stream().findFirst();}
    public Optional<InviterProfile> inviterProfile(String uid){return jdbc.query("SELECT firebase_uid,name,photo_url FROM public.users WHERE firebase_uid=?",(r,n)->new InviterProfile(r.getString("firebase_uid"),r.getString("name"),r.getString("photo_url")),uid).stream().findFirst();}
    public record TeamRow(String userId,String name,String photoUrl,String parentId,int level){}
    public List<TeamRow> team(String root){
        return jdbc.query("""
                WITH RECURSIVE team(user_id,parent_id,level,path) AS (
                    SELECT u.firebase_uid,NULL::varchar,0,ARRAY[u.firebase_uid]::varchar[]
                    FROM public.users u WHERE u.firebase_uid=?
                    UNION ALL
                    SELECT r.invitee_id,r.inviter_id,t.level+1,t.path||r.invitee_id
                    FROM app_private.referrals r JOIN team t ON r.inviter_id=t.user_id
                    WHERE NOT r.invitee_id=ANY(t.path)
                )
                SELECT t.user_id,u.name,u.photo_url,t.parent_id,t.level
                FROM team t JOIN public.users u ON u.firebase_uid=t.user_id
                ORDER BY t.level,t.user_id
                """,(rs,n)->new TeamRow(rs.getString("user_id"),rs.getString("name"),rs.getString("photo_url"),rs.getString("parent_id"),rs.getInt("level")),root);
    }
    public String code(String uid){return jdbc.query("SELECT referral_code FROM public.users WHERE firebase_uid=?",(r,n)->r.getString(1),uid).stream().findFirst().orElseThrow(CommerceService::notFound);}
    public String inviter(String code){return jdbc.query("SELECT firebase_uid FROM public.users WHERE referral_code=?",(r,n)->r.getString(1),code).stream().findFirst().orElseThrow(()->CommerceService.invalid("Invalid invitation code."));}
    public boolean hasPurchases(String uid){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=?)",Boolean.class,uid));}
    public boolean hasPaidPurchases(String uid){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=? AND payment_status='PAID')",Boolean.class,uid));}
    public boolean createsCycle(String inviter,String invitee){return Boolean.TRUE.equals(jdbc.queryForObject("""
            WITH RECURSIVE ancestors(uid) AS (SELECT CAST(? AS varchar(128)) UNION SELECT r.inviter_id FROM app_private.referrals r JOIN ancestors a ON r.invitee_id=a.uid)
            SELECT EXISTS(SELECT 1 FROM ancestors WHERE uid=?)
            """,Boolean.class,inviter,invitee));}
    public void bind(String inviter,String invitee,String code){bind(inviter,invitee,code,Instant.now());}
    public void bind(String inviter,String invitee,String code,Instant now){jdbc.update("INSERT INTO app_private.referrals(id,inviter_id,invitee_id,referral_code,created_at) VALUES (?,?,?,?,?)",UUID.randomUUID(),inviter,invitee,code,Timestamp.from(now));}
    public boolean rewarded(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.referral_rewards WHERE referral_id=?)",Boolean.class,id));}
    public void reward(Referral referral,UUID purchase,String uid,String role,UUID ledger){jdbc.update("INSERT INTO app_private.referral_rewards(id,referral_id,purchase_id,beneficiary_id,role,ledger_id) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),referral.id(),purchase,uid,role,ledger);}
    public long invited(String uid,boolean successful){return jdbc.queryForObject("SELECT count(*) FROM app_private.referrals r WHERE inviter_id=?"+(successful?" AND EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=r.invitee_id AND payment_status='PAID')":""),Long.class,uid);}
    public long rewards(String uid){return jdbc.queryForObject("SELECT COALESCE(sum(amount_paise),0) FROM app_private.referral_rewards WHERE beneficiary_id=?",Long.class,uid);}
    public Page<Invitation> invitations(String owner,ReferralFilter f){
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        if(owner!=null){where.append(" AND r.inviter_id=?");args.add(owner);}
        for(var field:List.of(new String[]{"r.inviter_id","i.name",f.inviter()},new String[]{"r.invitee_id","u.name",f.invitee()})){
            if(field[2]!=null && !field[2].isBlank()){where.append(" AND (position(lower(?) in lower("+field[0]+"))>0 OR position(lower(?) in lower(COALESCE("+field[1]+",'')))>0)");args.add(field[2].trim());args.add(field[2].trim());}
        }
        if(f.code()!=null && !f.code().isBlank()){where.append(" AND r.referral_code=?");args.add(f.code().trim().toUpperCase(Locale.ROOT));}
        dates(where,args,"r.created_at",f.from(),f.to());
        if(f.purchaseStatus()!=null){where.append(" AND (p.id IS NOT NULL)=?");args.add(f.purchaseStatus().equals("PAID"));}
        if(f.rewardStatus()!=null){where.append(" AND state.reward_status=?");args.add(f.rewardStatus().equals("CREDITED")?"CLAIMED":f.rewardStatus());}
        String joins="""
                FROM app_private.referrals r JOIN public.users i ON i.firebase_uid=r.inviter_id JOIN public.users u ON u.firebase_uid=r.invitee_id
                LEFT JOIN app_private.referral_rewards rw ON rw.referral_id=r.id AND rw.role='INVITER'
                LEFT JOIN app_private.referral_rewards re ON re.referral_id=r.id AND re.role='INVITEE'
                LEFT JOIN app_private.machine_reward_claims mc ON mc.source_id=r.id AND mc.user_id=r.inviter_id AND mc.reward_type='REFERRAL' AND mc.status<>'SUPERSEDED'
                LEFT JOIN LATERAL (SELECT * FROM app_private.product_purchases pp WHERE pp.user_id=r.invitee_id AND pp.payment_status='PAID' AND pp.paid_at>=r.created_at ORDER BY pp.paid_at,pp.id LIMIT 1) p ON true
                CROSS JOIN LATERAL (SELECT CASE WHEN mc.status='CLAIMED' THEN 'CLAIMED' WHEN mc.id IS NOT NULL THEN 'COMPLETED' WHEN rw.id IS NOT NULL THEN 'CLAIMED'
                 WHEN NOT EXISTS(SELECT 1 FROM app_private.product_purchases ip WHERE ip.user_id=r.inviter_id AND ip.payment_status='PAID') THEN 'LOCKED' ELSE 'PENDING' END AS reward_status) state
                """;
        long total=jdbc.queryForObject("SELECT count(*) "+joins+where,Long.class,args.toArray());args.add(f.pageSize());args.add((long)f.pageNumber()*f.pageSize());
        String sql="SELECT r.*,i.name AS inviter_name,u.name AS invitee_name,u.created_at AS registered,p.id AS purchase_id,p.product_title,p.purchase_amount_paise,COALESCE(mc.ledger_id,rw.ledger_id) AS inviter_ledger,re.ledger_id AS invitee_ledger,state.reward_status,COALESCE(rw.amount_paise,0)+COALESCE(mc.amount_paise,0) AS inviter_amount,COALESCE(re.amount_paise,0) AS invitee_amount "+joins+where+" ORDER BY r.created_at "+("oldest".equals(f.sort())?"ASC":"DESC")+",r.id LIMIT ? OFFSET ?";
        var rows=jdbc.query(sql,(r,n)->{boolean paid=r.getObject("purchase_id")!=null;return new Invitation(r.getObject("id",UUID.class),r.getString("inviter_id"),r.getString("inviter_name"),r.getString("invitee_id"),r.getString("invitee_name"),r.getString("referral_code"),instant(r,"registered"),instant(r,"created_at"),paid?"PAID":"PENDING",r.getObject("purchase_id",UUID.class),r.getString("product_title"),paid?r.getLong("purchase_amount_paise"):null,r.getString("reward_status"),r.getLong("inviter_amount"),r.getLong("invitee_amount"),r.getObject("inviter_ledger",UUID.class),r.getObject("invitee_ledger",UUID.class));},args.toArray());
        return new Page<>(rows,f.pageNumber(),f.pageSize(),total,(total+f.pageSize()-1)/f.pageSize());
    }
    private static void dates(StringBuilder where,List<Object> args,String field,LocalDate from,LocalDate to){
        if(from!=null){where.append(" AND "+field+">=?");args.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));}
        if(to!=null){where.append(" AND "+field+"<?");args.add(Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));}
    }
}
