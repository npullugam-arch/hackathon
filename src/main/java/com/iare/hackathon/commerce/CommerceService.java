package com.iare.hackathon.commerce;

import static com.iare.hackathon.commerce.CommerceDtos.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.iare.hackathon.wallet.ProductPaymentGateway;
import com.iare.hackathon.wallet.RazorpayGateway;
import com.iare.hackathon.wallet.WalletRepository;
import com.iare.hackathon.phototask.MachineRewardRepository;
import com.iare.hackathon.phototask.MachineRewardDtos;
import com.iare.hackathon.withdrawal.WithdrawalService;

@Service
public class CommerceService {
    private final ObjectProvider<CommerceRepository> repositories;
    private final ProductPaymentGateway payments;
    private final ObjectProvider<WalletRepository> wallets;
    private final WithdrawalService winnings;
    private final CommerceConfiguration.ClaimSchedule schedule;
    private final Clock clock;
    private final Validator validator;
    private final ObjectProvider<MachineRewardRepository> machineRewards;
        public CommerceService(ObjectProvider<CommerceRepository> repositories,ProductPaymentGateway payments,ObjectProvider<WalletRepository> wallets,WithdrawalService winnings,
            CommerceConfiguration.ClaimSchedule schedule,Clock clock,Validator validator,ObjectProvider<MachineRewardRepository> machineRewards){this.repositories=repositories;this.payments=payments;this.wallets=wallets;this.winnings=winnings;this.schedule=schedule;this.clock=clock;this.validator=validator;this.machineRewards=machineRewards;}
    private CommerceRepository repository(){var r=repositories.getIfAvailable();if(r==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Product account storage is temporarily unavailable.");return r;}
    private WalletRepository wallet(){var r=wallets.getIfAvailable();if(r==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recharge Balance storage is temporarily unavailable.");return r;}
    private void validate(Object input){if(input==null||!validator.validate(input).isEmpty())throw invalid("Check the required fields, filters and pagination values.");}
    public Checkout buy(String uid,Buy input){
        validate(input);
        var r=repository();UUID id=r.transaction(()->{
            r.lockReferralGraph();var referral=r.referral(uid);var beneficiaries=new ArrayList<String>();beneficiaries.add(uid);referral.ifPresent(v->beneficiaries.add(v.inviter()));
            r.lockUsers(beneficiaries);var existing=r.existing(uid,input.productId());if(existing.isPresent()){
                if(!existing.get().paymentStatus().equals("PAID"))throw conflict("This product has a saved unpaid order. Check its provider payment status before retrying.");
                throw conflict("You have already purchased this product. Each product can only be purchased once.");
            }
            var terms=r.terms(input.productId());
            if(!terms.active())throw conflict("This product is no longer available for new purchases.");
            if(terms.price()<100||terms.price()>10_000_000L)throw invalid("This product needs a purchase price between ₹1 and ₹100,000.");
            if(terms.minimum()<=0 || terms.minimum()>terms.daily())throw invalid("This product requires a positive daily income range.");
            if(Math.multiplyExact(terms.daily(),terms.duration())>9_007_199_254_740_991L)throw invalid("This product's earning amount exceeds the supported limit.");
            UUID purchaseId=r.intent(uid,terms,schedule);var pending=r.row(purchaseId,uid);
            long balance=wallet().debitForPurchase(uid,purchaseId,pending.price());
            Instant now=clock.instant(),activation=now;var zone=ZoneId.of(pending.zone());
            Instant first=PurchaseLifecycle.firstClaim(activation,zone,pending.time());Instant end=first.atZone(zone).toLocalDate().plusDays(pending.duration()).atStartOfDay(zone).toInstant();
            r.paidFromWallet(pending,now,first,end);

            return purchaseId;
        });
        return new Checkout(PurchaseLifecycle.view(r.row(id,uid),clock.instant()),wallet().balance(uid),true);
    }
    public Checkout checkout(String uid,UUID id){var r=repository();return r.transaction(()->{
        r.lockUsers(List.of(uid));r.row(id,uid);r.lockPurchase(id);var p=r.row(id,uid);
        if(!p.paymentStatus().equals("PAID"))throw conflict("Product purchases use Recharge Balance. Start the purchase again to debit the current balance.");
        return new Checkout(PurchaseLifecycle.view(p,clock.instant()),wallet().balance(uid),true);
    });}
    public Purchase verify(String uid,UUID id,Verify input){validate(input);var p=repository().row(id,uid);
        if(p.orderId()==null)throw conflict("No provider order exists yet. Resume this purchase first.");
        payments.verifySignature(p.orderId(),input.paymentId(),input.signature());
        if(p.paymentStatus().equals("PAID")){if(!input.paymentId().equals(p.paymentId()))throw conflict("This purchase was completed using a different payment.");return PurchaseLifecycle.view(p,clock.instant());}
        return applyPayment(p,checkedPayment(input.paymentId()));
    }
    public Purchase reconcile(String uid,UUID id){var p=repository().row(id,uid);
        if(p.paymentStatus().equals("PAID")||p.orderId()==null)return PurchaseLifecycle.view(p,clock.instant());
        var ids=payments.paymentIds(p.orderId());Purchase result=PurchaseLifecycle.view(p,clock.instant());
        for(String payment:ids){result=applyPayment(p,checkedPayment(payment));if(result.paymentStatus().equals("PAID"))break;}
        return result;
    }
    public void webhookPayment(String order,String payment){
        if(order==null||payment==null||!order.matches("order_[A-Za-z0-9]{1,64}")||!payment.matches("pay_[A-Za-z0-9]{1,64}"))throw invalid("Invalid payment event.");
        var p=repository().byOrder(order);if(p.isEmpty()||p.get().paymentStatus().equals("PAID"))return;
        applyPayment(p.get(),checkedPayment(payment));
    }
    private RazorpayGateway.Payment checkedPayment(String id){
        var payment=payments.payment(id);
        if(payment==null||!id.equals(payment.id()))throw invalid("The provider returned a different payment.");
        return payment;
    }
    private Purchase applyPayment(CommerceRepository.Row expected,RazorpayGateway.Payment payment){
        if(payment==null||payment.id()==null||!payment.id().matches("pay_[A-Za-z0-9]{1,64}")||!expected.orderId().equals(payment.order_id())||payment.amount()!=expected.price()||!"INR".equals(payment.currency()))
            throw invalid("The provider payment does not match this purchase.");
        var r=repository();return r.transaction(()->{
            // Referral binding cannot change while finalization identifies and locks the beneficiaries.
            r.lockReferralGraph();var referral=r.referral(expected.uid());var users=new ArrayList<String>();users.add(expected.uid());referral.ifPresent(v->users.add(v.inviter()));
            r.lockUsers(users);r.lockPurchase(expected.id());var p=r.row(expected.id(),expected.uid());
            if(p.paymentStatus().equals("PAID"))return PurchaseLifecycle.view(p,clock.instant());
            if(!"captured".equals(payment.status())||!payment.captured()||payment.amount_refunded()!=0){
                r.observed(p.id(),"failed".equals(payment.status()));return PurchaseLifecycle.view(r.row(p.id(),p.uid()),clock.instant());
            }
            Instant now=clock.instant(),activation=now;var zone=ZoneId.of(p.zone());
            Instant first=PurchaseLifecycle.firstClaim(activation,zone,p.time());Instant end=first.atZone(zone).toLocalDate().plusDays(p.duration()).atStartOfDay(zone).toInstant();
            r.paid(p,payment.id(),now,first,end);
            return PurchaseLifecycle.view(r.row(p.id(),p.uid()),now);
        });
    }
    public ClaimResult claim(String uid,UUID id){var r=repository();return r.transaction(()->{
        r.lockUsers(List.of(uid));r.row(id,uid);r.lockPurchase(id);var p=r.row(id,uid);Instant now=clock.instant();var view=PurchaseLifecycle.view(p,now);
        if(view.claimDay()==null||p.daily()<=0)throw conflict("No daily claim is available for this product at this time.");
        int day=view.claimDay();var old=r.claimed(id,day);
        if(old.isPresent()){var c=old.get();return new ClaimResult(c.id(),c.ledgerId(),day,c.amountPaise(),true,view);}
        if(!"ACTIVE".equals(view.status())||day>p.duration()||view.claimablePaise()<p.minimum()||view.claimablePaise()>p.daily()||p.claimed()+view.claimablePaise()>view.totalExpectedPaise())
            throw conflict("The product has no remaining eligible daily income.");
        String source="product-claim:"+id+":"+day;
        winnings.creditEligibleEarning(uid,source,BigDecimal.valueOf(view.claimablePaise(),2));
        Instant eligible=PurchaseLifecycle.eligibleAt(p,day);
        var c=r.claim(p,day,eligible,r.earningLedger(uid,source),now);
        return new ClaimResult(c.id(),c.ledgerId(),day,c.amountPaise(),false,PurchaseLifecycle.view(r.row(id,uid),now));
    });}
    public SimulatedPerformance performance(String uid,UUID id,int interval,int range){
        return DailyEarningCycles.performance(repository().row(id,uid),clock.instant(),interval,range);
    }
    public PurchaseDetail detail(String uid,UUID id){var r=repository();return new PurchaseDetail(PurchaseLifecycle.view(r.row(id,uid),clock.instant()),r.claims(id),r.paymentTransaction(id));}
    public Page<Purchase> purchases(String uid,Filter filter){validate(filter);dates(filter.from(),filter.to());var page=repository().purchases(uid,filter,clock.instant());Instant now=clock.instant();
        return new Page<>(page.items().stream().map(p->PurchaseLifecycle.view(p,now)).toList(),page.page(),page.size(),page.total(),page.totalPages());}
    public Page<ProductSales> sales(Filter filter){validate(filter);dates(filter.from(),filter.to());return repository().sales(filter,clock.instant());}
    public InvitationDashboard invitations(String uid,ReferralFilter filter){validate(filter);dates(filter.from(),filter.to());var r=repository();var bound=r.referral(uid);
        var inviter=bound.flatMap(ref->r.inviterProfile(ref.inviter())).map(profile->new InviterProfile(profile.userId(),profile.name(),profile.photoUrl())).orElse(null);
        var rewardRepository=machineRewards.getIfAvailable();var claims=rewardRepository==null?List.<MachineRewardDtos.Reward>of():rewardRepository.list(uid,"REFERRAL");long machineTotal=rewardRepository==null?0:rewardRepository.total(uid);
        return new InvitationDashboard(uid,r.code(uid),bound.map(CommerceRepository.Referral::code).orElse(null),inviter,r.invited(uid,false),r.invited(uid,true),r.rewards(uid)+machineTotal,winnings.dashboard(uid).availableWinningPaise(),bound.isEmpty()&&!r.hasPurchases(uid),r.hasPaidPurchases(uid),claims,r.invitations(uid,filter));}
    public Page<Invitation> adminInvitations(ReferralFilter filter){validate(filter);dates(filter.from(),filter.to());return repository().invitations(null,filter);}
    public TeamNode team(String uid){
        var rows=repository().team(uid);if(rows.isEmpty())throw notFound();
        var nodes=new LinkedHashMap<String,TeamNode>();for(var row:rows)nodes.put(row.userId(),new TeamNode(row.userId(),row.name(),row.photoUrl(),row.level(),new ArrayList<>()));
        for(var row:rows)if(row.parentId()!=null){var parent=nodes.get(row.parentId());if(parent!=null)parent.children().add(nodes.get(row.userId()));}
        return nodes.get(uid);
    }
    public InvitationDashboard bind(String uid,Bind input){validate(input);String code=input.code().toUpperCase(Locale.ROOT);var r=repository();r.transaction(()->{
        r.lockReferralGraph();String inviter=r.inviter(code);r.lockUsers(List.of(inviter,uid));
        if(inviter.equals(uid))throw invalid("You cannot use your own invitation code.");
        if(!r.hasPaidPurchases(inviter))throw conflict("Purchase any product to unlock Refer & Earn.");
        var old=r.referral(uid);if(old.isPresent()){if(old.get().code().equals(code))return null;throw conflict("Your inviter has already been set and cannot be changed.");}
        if(r.hasPurchases(uid))throw conflict("Enter an invitation code before starting your first product purchase.");
        if(r.createsCycle(inviter,uid))throw invalid("This invitation would create a circular referral.");
        r.bind(inviter,uid,code,clock.instant());return null;
    });return invitations(uid,new ReferralFilter(null,null,null,null,null,null,null,null,null,null));}
    private static void dates(LocalDate from,LocalDate to){
        for(var date:new LocalDate[]{from,to})if(date!=null&&(date.getYear()<2000||date.getYear()>2100))throw invalid("Choose dates between 2000 and 2100.");
        if(from!=null&&to!=null&&to.isBefore(from))throw invalid("The end date must follow the start date.");
    }
    static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Product purchase or account not found.");}
}
