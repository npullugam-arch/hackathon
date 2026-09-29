package com.iare.hackathon.activity;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.*;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;
class ActivityDatabaseTests {
 @Test void realDatabaseTakesPriorityAndUsesDayAndSignInBoundaries() throws Exception {
  try(var db=EmbeddedPostgres.builder().setPort(0).start()){
   var jdbc=new JdbcTemplate(db.getPostgresDatabase());jdbc.execute("CREATE SCHEMA app_private");jdbc.execute("CREATE TABLE public.users(last_login_at TIMESTAMPTZ)");jdbc.execute("CREATE TABLE app_private.product_daily_claims(amount_paise BIGINT,claimed_at TIMESTAMPTZ)");
   Instant now=Instant.now();Instant midnight=now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
   jdbc.update("INSERT INTO public.users VALUES (?),(?),(?)",Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.minusSeconds(90000)),Timestamp.from(now.plusSeconds(90000)));
   jdbc.update("INSERT INTO app_private.product_daily_claims VALUES (12345,?),(99999,?),(99999,?)",Timestamp.from(midnight),Timestamp.from(midnight.minusSeconds(1)),Timestamp.from(now.plusSeconds(90000)));
   var beans=new StaticListableBeanFactory();beans.addBean("jdbc",jdbc);var service=new ActivityService(beans.getBeanProvider(JdbcTemplate.class),new ActivityProperties(true,"Asia/Kolkata",50000,200000,300000));
   var result=service.current();assertEquals("REAL",result.mode());assertEquals(1,result.activeUsers());assertEquals(12345,result.claimedPaise());assertSame(result,service.current());
  }
 }
}
