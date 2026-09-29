package com.iare.hackathon.activity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ActivityServiceTests {
 ActivityProperties properties(boolean demo){return new ActivityProperties(demo,"Asia/Kolkata",50000,200000,300000);}
 @Test void simulationIsBoundedMonotonicAndResetsAtLocalMidnight(){
  var p=properties(true);var start=LocalDate.of(2026,9,28).atStartOfDay(ZoneId.of(p.timezone())).toInstant();long last=0;long min=1001,max=0;
  for(int second=0;second<86400;second+=20){var s=ActivityService.simulate(start.plusSeconds(second),p);assertEquals("SIMULATED",s.mode());assertTrue(s.activeUsers()>=500&&s.activeUsers()<=1000);assertTrue(s.claimedPaise()>=last);last=s.claimedPaise();min=Math.min(min,s.activeUsers());max=Math.max(max,s.activeUsers());}
  assertTrue(max>min);assertTrue(last>=20000000&&last<=30000000);assertEquals(5000000,ActivityService.simulate(start,p).claimedPaise());assertEquals(5000000,ActivityService.simulate(start.plusSeconds(86400),p).claimedPaise());
 }
 @Test void demoRequiresExplicitEnablementAndInvalidRangesFail(){
  var beans=new StaticListableBeanFactory();assertThrows(ResponseStatusException.class,()->new ActivityService(beans.getBeanProvider(JdbcTemplate.class),properties(false)).current());
  assertEquals("SIMULATED",new ActivityService(beans.getBeanProvider(JdbcTemplate.class),properties(true)).current().mode());
  assertThrows(IllegalArgumentException.class,()->new ActivityProperties(true,"Asia/Kolkata",50000,40000,300000));
 }
 @Test void databaseFailureNeverBecomesSimulatedData(){
  var jdbc=mock(JdbcTemplate.class,invocation->{throw new DataAccessResourceFailureException("Test outage");});var beans=new StaticListableBeanFactory();beans.addBean("jdbc",jdbc);
  assertThrows(DataAccessResourceFailureException.class,()->new ActivityService(beans.getBeanProvider(JdbcTemplate.class),properties(true)).current());
 }
}
