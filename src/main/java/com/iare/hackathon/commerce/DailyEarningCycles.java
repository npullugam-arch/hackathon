package com.iare.hackathon.commerce;

import java.math.*;
import java.time.*;
import java.util.*;
import static com.iare.hackathon.commerce.CommerceDtos.*;

/** Display progression only. Claims always use the immutable amount saved for that day. */
public final class DailyEarningCycles {
    public static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private DailyEarningCycles(){}
    public static DailyEarningCycle view(CommerceRepository.Row purchase,Instant now){
        if(!"PAID".equals(purchase.paymentStatus()) || now.isBefore(purchase.firstClaim()))return null;
        LocalDate first=purchase.firstClaim().atZone(IST).toLocalDate();
        LocalDate last=first.plusDays(purchase.duration()-1),today=now.atZone(IST).toLocalDate();
        LocalDate date=today.isAfter(last)?last:today;
        int day=(int)java.time.temporal.ChronoUnit.DAYS.between(first,date);
        if(day<0 || day>=purchase.amounts().size())return null;
        Instant start=date.atStartOfDay(IST).toInstant(),end=date.plusDays(1).atStartOfDay(IST).toInstant(),settles=end.minusSeconds(1);
        long duration=Duration.between(start,settles).toMillis();
        long elapsed=Math.max(0,Math.min(duration,Duration.between(start,now).toMillis()));
        long amount=purchase.amounts().get(day);
        long accrued=BigInteger.valueOf(amount).multiply(BigInteger.valueOf(elapsed)).divide(BigInteger.valueOf(duration)).longValueExact();
        BigDecimal progress=BigDecimal.valueOf(elapsed).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(duration),2,RoundingMode.DOWN);
        return new DailyEarningCycle(date,start,end,settles,amount,accrued,progress,now.isBefore(end)?"ACTIVE":"CLOSED");
    }
    public static SimulatedPerformance performance(CommerceRepository.Row p,Instant now,int interval,int range){
        if(!Set.of(1,5,15,60).contains(interval)||!Set.of(1,6,24).contains(range))throw CommerceService.invalid("Choose a supported chart interval and time range.");
        var cycle=view(p,now);var candles=new ArrayList<PerformanceCandle>();
        if(cycle!=null){
            long elapsed=Math.max(0,Math.min(86400,Duration.between(cycle.startsAt(),now).getSeconds()));
            long width=interval*60L,from=Math.max(0,elapsed-range*3600L)/width*width;
            long seed=p.id().getMostSignificantBits()^p.id().getLeastSignificantBits()^cycle.date().toEpochDay()^cycle.finalAmountPaise();
            for(long start=from;start<Math.max(1,elapsed);start+=width){
                long end=Math.min(elapsed,start+width);double open=signal(start,seed),close=signal(end,seed),high=Math.max(open,close),low=Math.min(open,close);
                // One underlying five-second signal is shared by all interval aggregations.
                for(long t=start+5;t<end;t+=5){double value=signal(t,seed);high=Math.max(high,value);low=Math.min(low,value);}
                candles.add(new PerformanceCandle(cycle.startsAt().plusSeconds(start),cycle.startsAt().plusSeconds(end),open,high,low,close,end==start+width));
            }
        }
        return new SimulatedPerformance("SIMULATED PERFORMANCE","Illustrative index units; no monetary value",now,cycle,interval,range,List.copyOf(candles));
    }
    private static double signal(long seconds,long seed){
        double cycle=Math.min(1,seconds/86399.0);
        // Smooth deterministic value noise at several time scales avoids repetitive candles.
        // This dimensionless index has no path into claim or wallet calculations.
        return 100+cycle*8+noise(seconds,7200,seed)*5+noise(seconds,900,seed+1)*2.8
                +noise(seconds,90,seed+2)*.9+noise(seconds,13,seed+3)*.3;
    }
    private static double noise(long seconds,long period,long seed){
        long cell=seconds/period;double t=(seconds%period)/(double)period,smooth=t*t*(3-2*t);
        return value(seed,cell)*(1-smooth)+value(seed,cell+1)*smooth;
    }
    private static double value(long seed,long cell){
        long z=seed+cell*0x9E3779B97F4A7C15L;
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;z=(z^(z>>>27))*0x94D049BB133111EBL;z^=z>>>31;
        return (z>>>11)*0x1.0p-53*2-1;
    }
}
