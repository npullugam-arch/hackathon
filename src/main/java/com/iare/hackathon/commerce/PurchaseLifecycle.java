package com.iare.hackathon.commerce;

import java.time.*;
import java.time.temporal.ChronoUnit;
import com.iare.hackathon.commerce.CommerceRepository.Row;
import com.iare.hackathon.commerce.CommerceDtos.Purchase;

public final class PurchaseLifecycle {
    private PurchaseLifecycle(){}
    public static Instant firstClaim(Instant activation,ZoneId zone,LocalTime time){return activation;}
    public static Instant eligibleAt(Row p,int day){
        return day==1?p.firstClaim():p.firstClaim().atZone(ZoneId.of(p.zone())).toLocalDate().plusDays(day-1).atTime(p.time()).atZone(ZoneId.of(p.zone())).toInstant();
    }
    public static Purchase view(Row p,Instant now){
        boolean paid="PAID".equals(p.paymentStatus()),started=paid&&!now.isBefore(p.firstClaim()),expired=paid&&!now.isBefore(p.end());
        int elapsed=started?(int)ChronoUnit.DAYS.between(p.firstClaim().atZone(ZoneId.of(p.zone())).toLocalDate(),now.atZone(ZoneId.of(p.zone())).toLocalDate()):0;
        if(started&&now.atZone(ZoneId.of(p.zone())).toLocalTime().isBefore(p.time()))elapsed--;
        elapsed=Math.max(0,Math.min(p.duration(),elapsed));
        int day=started&&!expired?elapsed+1:0;
        boolean complete=expired||(paid&&p.claimCount()>=p.duration());
        boolean claimable=!complete&&day>0&&day<=p.duration()&&p.lastClaimDay()!=day&&p.daily()>0;
        long today=day>0?p.amounts().get(day-1):0;
        long available=claimable?today:0;
        long total=Math.multiplyExact(p.daily(),p.duration());
        int released=started?Math.min(p.duration(),elapsed+1):0;
        long current=p.amounts().stream().limit(released).mapToLong(Long::longValue).sum();
        long missed=Math.max(0,current-p.claimed()-available);
        long future=complete?0:Math.multiplyExact(p.daily(),p.duration()-released);
        Instant next=null;
        if(paid&&!complete){if(!started)next=p.firstClaim();else if(claimable)next=eligibleAt(p,day);else if(day<p.duration())next=eligibleAt(p,day+1);}
        int completed=p.claimCount();
        return new Purchase(p.id(),p.uid(),p.userName(),p.email(),p.productId(),p.title(),p.image(),p.price(),p.daily(),p.duration(),p.zone(),p.time(),p.orderId(),p.paymentId(),p.paymentStatus(),p.paidAt(),p.firstClaim(),p.end(),p.configuredStart(),p.created(),paid?(complete?"COMPLETED":"ACTIVE"):"UNPAID",completed,p.duration()-completed,p.claimCount(),p.claimed(),total,total-p.price(),current,available,missed,future,next,day>0?day:null,p.minimum(),p.daily(),today,complete&&!p.amounts().isEmpty()?p.amounts().get(p.duration()-1):0,now,DailyEarningCycles.view(p,now));
    }
}
