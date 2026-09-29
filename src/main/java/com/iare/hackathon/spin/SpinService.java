package com.iare.hackathon.spin;

import static com.iare.hackathon.spin.SpinDtos.*;
import com.iare.hackathon.withdrawal.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SpinService {
    public static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final ObjectProvider<SpinRepository> spins;
    private final ObjectProvider<WithdrawalRepository> winnings;
    private final SpinPrizes prizes;
    private final Clock clock;
    private final WithdrawalEvents events;
    public SpinService(ObjectProvider<SpinRepository> spins,ObjectProvider<WithdrawalRepository> winnings,
                       SpinPrizes prizes,Clock clock,WithdrawalEvents events) {
        this.spins=spins;this.winnings=winnings;this.prizes=prizes;this.clock=clock;this.events=events;
    }
    private <T> T required(ObjectProvider<T> provider) {
        var result=provider.getIfAvailable();
        if(result==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Daily Spin is temporarily unavailable. Please try again later.");
        return result;
    }
    public State status(String uid) {
        var wallet=required(winnings);var repo=required(spins);
        return wallet.transaction(()->{wallet.lockUser(uid);return state(repo,uid,clock.instant());});
    }
    private State state(SpinRepository repo,String uid,Instant now) {
        var day=now.atZone(IST).toLocalDate();var today=repo.today(uid,day).orElse(null);
        return new State(uid,now,day,day.plusDays(1).atStartOfDay(IST).toInstant(),today==null,
            repo.balance(uid),prizes.prizes(),today,repo.recent(uid));
    }
    public Result spin(String uid,Request request) {
        if(request==null || request.requestId()==null || request.spinDay()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Refresh Daily Spin and try again with a valid request.");
        var wallet=required(winnings);var repo=required(spins);
        return wallet.transaction(()->{
            wallet.lockUser(uid);
            // Read the clock after waiting for the user lock, so midnight cannot authorize the wrong day.
            Instant now=clock.instant();var day=now.atZone(IST).toLocalDate();
            var replay=repo.replay(uid,request.requestId());
            if(replay.isPresent()) {
                if(!replay.get().spinDay().equals(request.spinDay()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"This spin request belongs to a different date. Refresh to continue.");
                return new Result(replay.get(),true,state(repo,uid,now));
            }
            if(!day.equals(request.spinDay())) throw new ResponseStatusException(HttpStatus.CONFLICT,"A new IST day has started, or the spin date is invalid. Refresh your eligibility before spinning.");
            var today=repo.today(uid,day);
            if(today.isPresent()) return new Result(today.get(),true,state(repo,uid,now));
            UUID id=UUID.randomUUID();long amount=prizes.draw();
            wallet.ledger(uid,null,"EARNING",amount,"daily-spin","daily-spin:"+id);
            repo.insert(id,uid,request.requestId(),amount,now,prizes.configuration());
            events.changedAfterCommit(uid);
            return new Result(repo.today(uid,day).orElseThrow(),false,state(repo,uid,now));
        });
    }
}
