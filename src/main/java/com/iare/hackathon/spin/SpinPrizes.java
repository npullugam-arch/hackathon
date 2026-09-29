package com.iare.hackathon.spin;

import java.security.SecureRandom;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SpinPrizes {
    private static final long[] AMOUNTS = {100,200,500,1000,3000,50000,1000000,7500000,10000000};
    private final int[] weights;
    private final int total;
    private final RandomGenerator random;
    @Autowired
    public SpinPrizes(@Value("${spin.reward-weights:90,50,25,12,7,4,3,2,1}") String configuration) {
        this(configuration, new SecureRandom());
    }
    SpinPrizes(String configuration, RandomGenerator random) {
        try { weights=Arrays.stream(configuration.split(",",-1)).map(String::trim).mapToInt(Integer::parseInt).toArray(); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Spin weights must be nine positive, decreasing integers.",ex); }
        if (weights.length!=AMOUNTS.length) throw new IllegalArgumentException("Configure exactly nine spin weights.");
        for (int i=0;i<weights.length;i++) {
            if (weights[i]<1 || weights[i]>1_000_000 || (i>0 && weights[i]>=weights[i-1]))
                throw new IllegalArgumentException("Spin weights must be positive and strictly decrease as prizes increase (maximum 1000000).");
        }
        total=Arrays.stream(weights).sum(); this.random=random;
    }
    public List<SpinDtos.Prize> prizes() {
        var values=new ArrayList<SpinDtos.Prize>();
        for(int i=0;i<AMOUNTS.length;i++) values.add(new SpinDtos.Prize(AMOUNTS[i],weights[i],total));
        return List.copyOf(values);
    }
    public long draw() {
        int eligibleTotal=Arrays.stream(weights,0,5).sum();
        int ticket=random.nextInt(eligibleTotal);
        for(int i=0;i<5;i++) { if(ticket<weights[i]) return AMOUNTS[i]; ticket-=weights[i]; }
        throw new IllegalStateException("Invalid random draw");
    }
    public String configuration() { return String.join(",",Arrays.stream(weights).mapToObj(Integer::toString).toList()); }
}

