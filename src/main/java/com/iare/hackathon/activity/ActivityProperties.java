package com.iare.hackathon.activity;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("app.activity")
public record ActivityProperties(@DefaultValue("false") boolean demoEnabled,
 @DefaultValue("Asia/Kolkata") String timezone, @DefaultValue("50000") long demoStartRupees,
 @DefaultValue("200000") long demoTargetMinRupees,@DefaultValue("300000") long demoTargetMaxRupees) {
 public ActivityProperties {
  ZoneId.of(timezone);
  if(demoStartRupees<0 || demoTargetMinRupees<demoStartRupees || demoTargetMaxRupees<demoTargetMinRupees || demoTargetMaxRupees>1000000000L)
   throw new IllegalArgumentException("Activity demo amounts must satisfy 0 <= start <= target min <= target max <= 1000000000.");
 }
}
