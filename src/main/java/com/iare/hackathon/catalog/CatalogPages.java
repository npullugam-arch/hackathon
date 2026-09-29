package com.iare.hackathon.catalog;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Controller
public class CatalogPages {
 private static final Map<String,String> FEATURES=Map.ofEntries(Map.entry("recharge","Recharge"),Map.entry("withdrawal","Withdrawal"),Map.entry("invitation","Invitation"),Map.entry("my-product","My Product"),Map.entry("renib-bonus","Spin & Earn"),Map.entry("customer-service","Customer Service"),Map.entry("my-team","My Team"),Map.entry("monthly-salary","Monthly Salary"),Map.entry("calculator","Calculator"));
 @GetMapping({"/products", "/products/{id}"}) public String products() { return "products"; }
 @GetMapping("/features/my-product/{id}") public String productOverview() { return "product-overview"; }
 @GetMapping("/home") public String home() { return "redirect:/dashboard"; }
 @GetMapping("/tasks/refer-earn") public String referralTask(){return "invitation";}
 @GetMapping("/tasks/take-photo") public String photoTask(){return "photo-task";}
 @GetMapping("/profile") public String profile() { return "profile"; }
 @GetMapping("/machines/{id}") public String machine() { return "machine"; }
 @GetMapping("/news") public String news(Model model) { return placeholder(model,"News","Updates, announcements and stories will find their home here."); }
 @GetMapping("/price") public String price(Model model) { return placeholder(model,"Price","A clear view of pricing is coming soon. Explore the Product page for currently available product details."); }
 @GetMapping("/features/{feature}") public String feature(@PathVariable String feature,Model model) {
 if("recharge".equals(feature))return "recharge";
 if("withdrawal".equals(feature))return "withdrawal";
 if("calculator".equals(feature))return "calculator";
 if("my-product".equals(feature))return "my-products";
 if("invitation".equals(feature))return "invitation";
 if("my-team".equals(feature))return "my-team";
 if("monthly-salary".equals(feature))return "monthly-salary";
 if("renib-bonus".equals(feature))return "spin";
 if("customer-service".equals(feature))return "customer-service";
 String title=FEATURES.get(feature);if(title==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
 return placeholder(model,title,"We are preparing your "+title+" experience. This feature is not available yet."); }
 private String placeholder(Model model,String title,String description) { model.addAttribute("pageTitle",title);model.addAttribute("pageDescription",description);return "placeholder"; }
}
