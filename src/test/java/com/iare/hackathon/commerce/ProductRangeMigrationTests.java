package com.iare.hackathon.commerce;

import static org.junit.jupiter.api.Assertions.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductRangeMigrationTests {
    @Test void upgradePreservesHistoricalPurchasesClaimsAndSchedules()throws Exception{
        try(var database=EmbeddedPostgres.builder().setPort(0).start()){
            var ds=database.getPostgresDatabase();
            Flyway.configure().dataSource(ds).schemas("app_private").defaultSchema("app_private").target("12").locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(ds);UUID product=UUID.randomUUID(),purchase=UUID.randomUUID(),ledger=UUID.randomUUID();
            jdbc.update("INSERT INTO public.users(firebase_uid,provider,firebase_created_at,last_login_at) VALUES ('legacy','google.com',1,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO public.products(id,title,image_url,original_price,discount_price,duration_days,daily_income,total_earnings,description,start_at,end_at,active) VALUES (?,'Legacy','/image',100,100,2,23.34,46.68,'Legacy','2026-09-20T00:00:00Z','2026-09-22T00:00:00Z',true)",product);
            jdbc.update("INSERT INTO app_private.product_purchases(id,user_id,product_id,product_title,image_url,purchase_amount_paise,daily_income_paise,duration_days,configured_start_at,claim_zone,claim_time,payment_status,paid_at,first_claim_at,end_at) VALUES (?,'legacy',?,'Legacy','/image',10000,2334,2,'2026-09-20T00:00:00Z','Asia/Kolkata','00:00','PAID','2026-09-20T06:00:00Z','2026-09-20T18:30:00Z','2026-09-22T18:30:00Z')",purchase,product);
            jdbc.update("INSERT INTO app_private.winning_transactions(id,user_id,kind,amount_paise,actor,source_id) VALUES (?,'legacy','EARNING',2334,'earning-service',?)",ledger,"product-claim:"+purchase+":1");
            jdbc.update("INSERT INTO app_private.product_daily_claims(id,purchase_id,user_id,day_number,eligible_at,amount_paise,ledger_id,claimed_at) VALUES (?,?,'legacy',1,'2026-09-20T18:30:00Z',2334,?,'2026-09-20T18:30:00Z')",UUID.randomUUID(),purchase,ledger);
            Flyway.configure().dataSource(ds).schemas("app_private").defaultSchema("app_private").locations("classpath:db/migration").load().migrate();
            var row=new CommerceRepository(jdbc).row(purchase,"legacy");assertEquals(2334,row.minimum());assertEquals(List.of(2334L,2334L),row.amounts());assertEquals(2334,row.claimed());assertEquals(1,row.claimCount());
            assertEquals(Instant.parse("2026-09-20T18:30:00Z"),row.firstClaim());assertEquals(Instant.parse("2026-09-22T18:30:00Z"),row.end());
            var view=PurchaseLifecycle.view(row,Instant.parse("2026-09-21T18:30:00Z"));assertEquals(2,view.claimDay());assertEquals(2334,view.claimablePaise());
        }
    }
}
