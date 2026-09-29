package com.iare.hackathon.phototask;

import static com.iare.hackathon.phototask.MachineRewardDtos.*;
import com.iare.hackathon.withdrawal.WithdrawalEvents;
import com.iare.hackathon.withdrawal.WithdrawalRepository;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MachineRewardService {
    private final ObjectProvider<MachineRewardRepository> rewards;
    private final ObjectProvider<WithdrawalRepository> wallets;
    private final WithdrawalEvents events;
    public MachineRewardService(ObjectProvider<MachineRewardRepository> rewards,ObjectProvider<WithdrawalRepository> wallets,WithdrawalEvents events){this.rewards=rewards;this.wallets=wallets;this.events=events;}
    private MachineRewardRepository rewardRepository(){var value=rewards.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Machine rewards are temporarily unavailable.");return value;}
    private WithdrawalRepository walletRepository(){var value=wallets.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Withdrawable balance is temporarily unavailable.");return value;}
    public ClaimResult claim(String uid,UUID id){var repo=rewardRepository();var wallet=walletRepository();return wallet.transaction(()->{
        long balance=wallet.lockUser(uid);var reward=repo.lock(id,uid);
        if(reward.status().equals("CLAIMED"))return new ClaimResult(reward,balance,true);
        if(!reward.status().equals("COMPLETED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This referral was already rewarded under the previous rules.");
        if(!repo.hasPaidPurchase(uid))throw new ResponseStatusException(HttpStatus.CONFLICT,reward.type().equals("TAKE_PHOTO")?"Purchase any product to unlock this task.":"Purchase any product to unlock Refer & Earn.");
        String source="machine-reward:"+reward.type()+":"+reward.sourceId();
        wallet.ledger(uid,null,"EARNING",reward.amountPaise(),"machine-reward",source);
        var claimed=repo.claim(reward,uid,wallet.earningLedger(uid,source));
        events.changedAfterCommit(uid);
        return new ClaimResult(claimed,wallet.lockUser(uid),false);
    });}
}