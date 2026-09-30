package com.iare.hackathon.catalog;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name="app.supabase.enabled", havingValue="true")
public class CatalogRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final RowMapper<Product> PRODUCT = (r, n) -> new Product(r.getObject("id", UUID.class),
            r.getString("title"), r.getString("image_url"), r.getBigDecimal("original_price"), r.getBigDecimal("discount_price"),
            r.getInt("duration_days"), r.getBigDecimal("daily_income"), r.getBigDecimal("total_earnings"), r.getString("description"),
            r.getBoolean("active"),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(), r.getLong("sold_count"), r.getBigDecimal("minimum_daily_income"), r.getString("country_name"), r.getString("country_url"), r.getString("tag"));
    private static final RowMapper<Advertisement> AD = (r, n) -> new Advertisement(r.getObject("id", UUID.class),
            r.getString("title"), r.getString("image_url"), r.getBoolean("active"),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant());
    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public Map<UUID, UUID> purchasesForUser(String uid) {
        Map<UUID, UUID> purchases = new LinkedHashMap<>();
        jdbc.query("SELECT product_id,id FROM app_private.product_purchases WHERE user_id=? AND payment_status='PAID'",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> purchases.put(row.getObject("product_id", UUID.class), row.getObject("id", UUID.class)), uid);
        return purchases;
    }
    public List<Product> products(boolean activeOnly) {
        return jdbc.query("SELECT p.*, (SELECT count(*) FROM app_private.product_purchases b WHERE b.product_id=p.id AND b.payment_status='PAID') AS sold_count FROM public.products p" + (activeOnly ? " WHERE (p.active OR EXISTS(SELECT 1 FROM app_private.product_purchases b WHERE b.product_id=p.id AND b.payment_status='PAID'))" : "") + " ORDER BY created_at DESC, id", PRODUCT);
    }
    public Optional<Product> product(UUID id, boolean activeOnly) {
        return jdbc.query("SELECT p.*, (SELECT count(*) FROM app_private.product_purchases b WHERE b.product_id=p.id AND b.payment_status='PAID') AS sold_count FROM public.products p WHERE p.id=?" + (activeOnly ? " AND (p.active OR EXISTS(SELECT 1 FROM app_private.product_purchases b WHERE b.product_id=p.id AND b.payment_status='PAID'))" : ""), PRODUCT, id).stream().findFirst();
    }
    public Product saveProduct(UUID id, ProductInput p, boolean create) {
        var total = p.dailyIncome().multiply(BigDecimal.valueOf(p.totalClaims()));
        Object[] values = {p.title().trim(), p.imageUrl().trim(), p.originalPrice(), p.discountPrice(), p.totalClaims(),
                p.dailyIncome(), p.minimumDailyIncome(), total, p.description().trim(), p.active(), p.countryName(), p.countryUrl(), p.tag(), id};
        String sql = create ? """
                INSERT INTO public.products (title,image_url,original_price,discount_price,duration_days,daily_income,minimum_daily_income,
                total_earnings,description,active,country_name,country_url,tag,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *, 0::bigint AS sold_count
                """ : """
                UPDATE public.products SET title=?,image_url=?,original_price=?,discount_price=?,duration_days=?,daily_income=?,minimum_daily_income=?,
                total_earnings=?,description=?,active=?,country_name=?,country_url=?,tag=?,updated_at=clock_timestamp() WHERE id=? RETURNING *, 0::bigint AS sold_count
                """;
        jdbc.query(sql, PRODUCT, values).stream().findFirst().orElseThrow(CatalogService::notFound);
        return product(id,false).orElseThrow(CatalogService::notFound);
    }
    public boolean deleteProduct(UUID id) { return jdbc.update("DELETE FROM public.products WHERE id=?", id) == 1; }
    public List<Advertisement> advertisements(boolean activeOnly) {
        return jdbc.query("SELECT * FROM public.advertisements" + (activeOnly ? " WHERE active" : "") + " ORDER BY updated_at DESC,id", AD);
    }
    public Advertisement saveAdvertisement(UUID id, AdvertisementInput ad, boolean create) {
        return transactions.execute(status -> {
            // Serialize activation across application instances, including when no ad currently exists.
            jdbc.execute("SELECT pg_advisory_xact_lock(812347901)");
            if (ad.active()) jdbc.update("UPDATE public.advertisements SET active=false,updated_at=clock_timestamp() WHERE active AND id<>?", id);
            String sql = create ? "INSERT INTO public.advertisements (title,image_url,active,id) VALUES (?,?,?,?) RETURNING *"
                    : "UPDATE public.advertisements SET title=?,image_url=?,active=?,updated_at=clock_timestamp() WHERE id=? RETURNING *";
            return jdbc.query(sql, AD, ad.title().trim(), ad.imageUrl().trim(), ad.active(), id).stream().findFirst().orElseThrow(CatalogService::notFound);
        });
    }
    public boolean deleteAdvertisement(UUID id) { return jdbc.update("DELETE FROM public.advertisements WHERE id=?", id) == 1; }

    private static final RowMapper<Machine> MACHINE = (r,n) -> new Machine(r.getObject("id",UUID.class),
        r.getString("name"),r.getString("image_url"),r.getString("profile_title"),r.getString("short_description"),
        r.getString("full_details"),r.getBoolean("active"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getString("task_type"));
    public List<Machine> machines(boolean activeOnly) {
        return jdbc.query("SELECT * FROM public.task_machines"+(activeOnly?" WHERE active":"")+" ORDER BY created_at,id",MACHINE);
    }
    public Optional<Machine> machine(UUID id, boolean activeOnly) {
        return jdbc.query("SELECT * FROM public.task_machines WHERE id=?"+(activeOnly?" AND active":""),MACHINE,id).stream().findFirst();
    }
    public Machine saveMachine(UUID id, MachineInput m, boolean create) {
        String sql=create ? "INSERT INTO public.task_machines(name,image_url,profile_title,short_description,full_details,active,id) VALUES(?,?,?,?,?,?,?) RETURNING *"
          : "UPDATE public.task_machines SET name=?,image_url=?,profile_title=?,short_description=?,full_details=?,active=?,updated_at=clock_timestamp() WHERE id=? RETURNING *";
        return jdbc.query(sql,MACHINE,m.name().trim(),m.imageUrl().trim(),m.profileTitle().trim(),m.shortDescription().trim(),m.fullDetails().trim(),m.active(),id)
            .stream().findFirst().orElseThrow(CatalogService::notFound);
    }
    public boolean deleteMachine(UUID id) { return jdbc.update("DELETE FROM public.task_machines WHERE id=?",id)==1; }
}
