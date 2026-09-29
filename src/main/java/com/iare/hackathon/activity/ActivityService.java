package com.iare.hackathon.activity;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Service
public class ActivityService {
 public record Snapshot(String mode,long activeUsers,long claimedPaise,Instant asOf,String timezone,String usersDefinition,String claimsDefinition) {}
 private final ObjectProvider<JdbcTemplate> databases;
 private final ActivityProperties properties;
 private Snapshot cached;
 public ActivityService(ObjectProvider<JdbcTemplate> databases,ActivityProperties properties){this.databases=databases;this.properties=properties;}
 public synchronized Snapshot current(){
  Instant now=Instant.now();if(cached!=null && now.isBefore(cached.asOf().plusSeconds(15)))return cached;
  var jdbc=databases.getIfAvailable();
  if(jdbc!=null){
   var day=now.atZone(ZoneId.of(properties.timezone())).toLocalDate();
   var start=day.atStartOfDay(ZoneId.of(properties.timezone())).toInstant();
   // A single statement gives a consistent snapshot. Claims are income credits, not net investment profit.
   cached=jdbc.queryForObject("""
     SELECT (SELECT count(*) FROM public.users WHERE last_login_at >= ? AND last_login_at <= ?) AS users,
       (SELECT COALESCE(sum(amount_paise),0) FROM app_private.product_daily_claims WHERE claimed_at >= ? AND claimed_at <= ?) AS claimed
     """,(r,n)->new Snapshot("REAL",r.getLong("users"),r.getLong("claimed"),now,properties.timezone(),
       "Distinct accounts signed in within the last 24 hours; not an online-user count.",
       "Product income credited through claims today; not net profit, withdrawals or guaranteed earnings."),
       Timestamp.from(now.minusSeconds(86400)),Timestamp.from(now),Timestamp.from(start),Timestamp.from(now));
  }else if(properties.demoEnabled())cached=simulate(now,properties);
  else throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Platform activity is currently unavailable.");
  return cached;
 }
 public static Snapshot simulate(Instant now,ActivityProperties p){
  var local=now.atZone(ZoneId.of(p.timezone()));var day=local.toLocalDate();
  long start=day.atStartOfDay(local.getZone()).toEpochSecond(),end=day.plusDays(1).atStartOfDay(local.getZone()).toEpochSecond();
  double progress=Math.max(0,Math.min(1,(now.getEpochSecond()-start)/(double)(end-start)));
  long seed=Math.floorMod(day.toEpochDay()*1103515245L+12345,2147483647L);
  long target=p.demoTargetMinRupees()+seed%(p.demoTargetMaxRupees()-p.demoTargetMinRupees()+1);
  // Positive derivative guarantees progression. Daily seeded phase varies the rate without random downward jumps.
  double phase=(seed%6283)/1000.0;
  double curve=progress+(Math.sin(2*Math.PI*progress+phase)-Math.sin(phase))/(4*Math.PI);
  long amount=Math.round((p.demoStartRupees()+(target-p.demoStartRupees())*curve)*100);
  long slot=(now.getEpochSecond()-start)/20;
  long users=500+Math.floorMod(seed+slot*7919,501);
  return new Snapshot("SIMULATED",users,amount,now,p.timezone(),"Demo values only. No real people are counted.",
    "Simulated amounts only. No real transactions or guaranteed earnings.");
 }
}
