package com.iare.hackathon.information;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice
@EnableConfigurationProperties(SiteProperties.class)
public class SiteModel {
 private final SiteProperties site;
 public SiteModel(SiteProperties site){this.site=site;}
 @ModelAttribute("siteInfo") public SiteProperties site(){return site;}
 @ModelAttribute("copyrightYear") public int year(){return java.time.Year.now().getValue();}
}
