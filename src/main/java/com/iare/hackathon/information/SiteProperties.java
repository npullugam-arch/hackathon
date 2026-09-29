package com.iare.hackathon.information;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("app.site")
public record SiteProperties(@DefaultValue("Launchpad") String companyName,@DefaultValue("") String supportEmail,
 @DefaultValue("") String instagramUrl,@DefaultValue("") String telegramUrl) {
 public SiteProperties {
  if(companyName==null||companyName.isBlank())companyName="Launchpad";
  supportEmail=supportEmail==null?"":supportEmail.trim();
  if(!supportEmail.isEmpty()&&!supportEmail.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))throw new IllegalArgumentException("Invalid public support email.");
  instagramUrl=check(instagramUrl);telegramUrl=check(telegramUrl);
 }
 private static String check(String value){if(value==null||value.isBlank())return "";var uri=URI.create(value.trim());
  if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Public social links must use HTTPS without credentials.");return uri.toString();}
}
