package com.iare.hackathon.spin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SpinPrizesTests {
    @Test void everyConfiguredTicketMapsToExactlyOneDisplayedPrize() {
        var random=mock(RandomGenerator.class);var prizes=new SpinPrizes("50,25,12,7,4,2",random);Map<Long,Integer> counts=new TreeMap<>();
        for(int ticket=0;ticket<100;ticket++){when(random.nextInt(100)).thenReturn(ticket);counts.merge(prizes.draw(),1,Integer::sum);}
        assertEquals(Map.of(100L,50,200L,25,500L,12,1000L,7,2000L,4,3000L,2),counts);
        assertEquals(List.of(100L,200L,500L,1000L,2000L,3000L),prizes.prizes().stream().map(SpinDtos.Prize::amountPaise).toList());
    }
    @Test void invalidZeroOrNonDecreasingWeightsFailStartup() {
        for(String value:List.of("", "50,25,12,7,4,0", "1,2,3,4,5,6", "1,1,1,1,1,1", "50,25", "50,25,12,7,4,-1", "x,25,12,7,4,2", "1000001,25,12,7,4,2"))
            assertThrows(IllegalArgumentException.class,()->new SpinPrizes(value),value);
    }
    @Test void customConfigurationKeepsEveryPrizePossible() {
        var prizes=new SpinPrizes("60,20,10,6,3,1");assertEquals(100,prizes.prizes().get(0).totalWeight());
        assertTrue(prizes.prizes().stream().allMatch(p->p.weight()>0));
    }
}
