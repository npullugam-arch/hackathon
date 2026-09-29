package com.iare.hackathon.withdrawal;

import static com.iare.hackathon.withdrawal.WithdrawalDtos.*;
import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WithdrawalService {
    private final ObjectProvider<WithdrawalRepository> repositories;
    private final BankDirectory directory;
    private final BankEncryption encryption;
    private final Validator validator;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private WithdrawalEvents events;
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(String uid) { return events.subscribe(uid); }
    private long minimumAmountPaise=1000;
    @org.springframework.beans.factory.annotation.Value("${withdrawal.minimum-amount:10}")
    public void setMinimumAmount(BigDecimal amount){
        try { minimumAmountPaise=amount.movePointRight(2).longValueExact(); }
        catch(ArithmeticException ex){throw new IllegalArgumentException("Withdrawal minimum must have at most two decimals",ex);}
        if(minimumAmountPaise<=0)throw new IllegalArgumentException("Withdrawal minimum must be positive");
    }
    public WithdrawalService(ObjectProvider<WithdrawalRepository> repositories, BankDirectory directory, BankEncryption encryption, Validator validator) {
        this.repositories=repositories; this.directory=directory; this.encryption=encryption; this.validator=validator;
    }
    private WithdrawalRepository repository() {
        var value = repositories.getIfAvailable();
        if (value == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Withdrawal storage is temporarily unavailable.");
        return value;
    }
    private void validate(Object value) {
        if (value == null || !validator.validate(value).isEmpty()) throw invalid("Check the amount, bank details and required fields.");
    }
    public List<Bank> directory() { return directory.banks(); }
    public Dashboard dashboard(String uid) { var d=repository().dashboard(uid,encryption.configured());return new Dashboard(d.userId(),d.totalRechargePaise(),d.totalWinningPaise(),d.availableWinningPaise(),d.reservedPaise(),d.bankSetupConfigured(),d.bankAccounts(),d.availableRechargePaise(),minimumAmountPaise); }
    public Snapshot snapshot(String uid, Filter filter) {
        return repository().snapshot(() -> new Snapshot(dashboard(uid),list(uid,filter)));
    }
    public List<BankAccount> banks(String uid) { return repository().banks(uid); }
    public BankAccount addBank(String uid, BankInput input) {
        validate(input);
        if (!input.accountNumber().equals(input.confirmAccountNumber())) throw invalid("The account numbers do not match.");
        if (!input.ifsc().startsWith(input.bankCode())) throw invalid("The IFSC must belong to the selected bank.");
        if (input.accountNumber().matches("0+")) throw invalid("Enter a valid bank account number.");
        String name=directory.name(input.bankCode());
        String fingerprint=encryption.fingerprint(uid,input.bankCode(),input.accountNumber());
        UUID id=UUID.randomUUID();
        String ciphertext=encryption.encrypt(input.accountNumber(),uid+":"+id);
        var repo=repository();
        return repo.transaction(() -> {
            repo.lockUser(uid);
            if (repo.duplicateBank(uid,fingerprint)) throw conflict("This bank account has already been added. Contact support if it was deactivated.");
            if (repo.banks(uid).size()>=10) throw invalid("You can save at most 10 active bank accounts.");
            return repo.saveBank(uid,id,input,name,ciphertext,fingerprint);
        });
    }
    public void deactivate(String uid, UUID id) {
        var repo=repository(); repo.transaction(() -> { repo.lockUser(uid); repo.deactivate(uid,id); return null; });
    }
    public Withdrawal create(String uid, Create request) {
        validate(request); long paise=paise(request.amount()); var repo=repository();
        return repo.transaction(() -> {
            long available=repo.lockUser(uid);
            var replay=repo.replay(uid,request.idempotencyKey());
            if (replay.isPresent()) {
                var previous=replay.get();
                if (previous.amountPaise()!=paise || !previous.bankAccount().id().equals(request.bankAccountId()))
                    throw conflict("This request ID was already used with different withdrawal details.");
                return previous;
            }
            if(paise<minimumAmountPaise)throw invalid("Minimum withdrawal is INR "+BigDecimal.valueOf(minimumAmountPaise,2).toPlainString()+".");
            repo.configureMinimum(minimumAmountPaise);
            if (!repo.bank(uid,request.bankAccountId()).active()) throw invalid("Choose an active bank account.");
            if (available<paise) throw invalid("Insufficient Winning Cash. Recharged money cannot be withdrawn.");
            UUID id=UUID.randomUUID(); repo.create(id,uid,request,paise); repo.audit(id,uid,"REQUESTED",null);
            if(events!=null)events.changedAfterCommit(uid);
            return repo.detail(id,uid);
        });
    }
    public Withdrawal detail(String uid, UUID id) { return repository().detail(id,uid); }
    public Page list(String uid, Filter filter) {
        validate(filter);
        if(filter.minAmount()!=null&&filter.maxAmount()!=null&&filter.minAmount().compareTo(filter.maxAmount())>0)throw invalid("Maximum amount must be at least the minimum amount.");
        if (filter.from()!=null && filter.to()!=null && filter.to().isBefore(filter.from())) throw invalid("The end date must follow the start date.");
        for (var date : new java.time.LocalDate[]{filter.from(),filter.to()})
            if (date!=null && (date.getYear()<2000 || date.getYear()>2100)) throw invalid("Choose dates between 2000 and 2100.");
        return repository().list(uid,filter);
    }
    public Withdrawal process(UUID id, Action input, String actor) {
        validate(input);
        var action=new Action(normalizeStatus(input.status()),clean(input.referenceId()),clean(input.adminRemark()),clean(input.failureReason()));
        if (action.status().equals("SUCCESSFUL") && (action.referenceId()==null || !action.referenceId().matches("[A-Za-z0-9][A-Za-z0-9._/-]{2,119}")))
            throw invalid("Enter the bank transfer reference (3–120 letters, digits, dots, slashes, underscores or hyphens).");
        boolean refund=Set.of("FAILED","REJECTED").contains(action.status());
        if (refund && (action.failureReason()==null || action.failureReason().length()<10 || !action.failureReason().matches("(?s).*[A-Za-z].*"))) throw invalid("Enter a professional reason of at least 10 characters, such as Technical issue during payment processing.");
        var repo=repository();
        return repo.transaction(() -> {
            var initial=repo.detail(id,null); repo.lockUser(initial.userId()); repo.lockWithdrawal(id); var w=repo.detail(id,null);
            if (!w.status().equals("PROCESSING")) {
                boolean same=(w.status().equals("SUCCESSFUL") && action.status().equals("SUCCESSFUL") && Objects.equals(w.referenceId(),action.referenceId())) ||
                        (w.status().equals("REFUNDED") && Objects.equals(w.failureKind(),action.status()) && Objects.equals(w.failureReason(),action.failureReason()));
                if (same && Objects.equals(w.adminRemark(),action.adminRemark())) return w;
                throw conflict("This withdrawal has already been finalized. Refresh its details.");
            }
            if (action.status().equals("PROCESSING") && w.processedAt()!=null) return w;
            repo.update(w,action,actor);
            if(events!=null)events.changedAfterCommit(w.userId());
            return repo.detail(id,null);
        });
    }
    static String normalizeStatus(String status) {
        return switch(status) { case "COMPLETED" -> "SUCCESSFUL"; case "ERROR" -> "FAILED";
            case "CANCELLED", "REFUNDED" -> "REJECTED"; default -> status; };
    }
    public AdminWithdrawal adminDetail(UUID id, String actor) {
        return repository().transaction(() -> new AdminWithdrawal(detail(null,id),payoutDetails(id,actor).accountNumber()));
    }
    public AdminPage adminList(Filter filter, String actor) {
        return repository().transaction(() -> {
            var page=list(null,filter);
            var items=page.items().stream().map(w -> new AdminWithdrawal(w,payoutDetails(w.id(),actor).accountNumber())).toList();
            return new AdminPage(items,page.page(),page.size(),page.total(),page.totalPages(),page.summary());
        });
    }
    public PayoutDetails payoutDetails(UUID id, String actor) {
        var repo=repository();
        return repo.transaction(() -> {
            var w=repo.detail(id,null);
            var bank=w.bankAccount();
            String number=encryption.decrypt(repo.ciphertext(w.userId(),bank.id()),w.userId()+":"+bank.id());
            repo.audit(id,actor,"BANK_DETAILS_VIEWED",null);
            return new PayoutDetails(number,bank.holderName(),bank.bankName(),bank.ifsc());
        });
    }
    /** Trusted earning integration only: the existing earning producer supplies its immutable event ID and already calculated eligible amount.
     * No HTTP endpoint exposes this operation, and product display values are never treated as earned money. */
    public void creditEligibleEarning(String uid, String sourceId, BigDecimal amount) {
        if (sourceId==null || sourceId.isBlank() || sourceId.length()>160 || amount==null || amount.signum()<=0)
            throw invalid("An eligible earning requires a source event and a positive amount.");
        long paise=paise(amount); var repo=repository();
        repo.transaction(() -> {
            repo.lockUser(uid); var previous=repo.earning(uid,sourceId);
            if (previous.isPresent()) {
                if (previous.get()!=paise) throw conflict("The earning event was already recorded with a different amount.");
            } else repo.ledger(uid,null,"EARNING",paise,"earning-service",sourceId);
            return null;
        });
    }
    private static long paise(BigDecimal amount) {
        try { return amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException ex) { throw invalid("Use a valid rupee amount with at most two decimal places."); }
    }
    private static String clean(String value) { return value==null || value.isBlank() ? null : value.trim(); }
    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Withdrawal or bank account not found."); }
}
