package com.iare.hackathon.commerce;

import static com.iare.hackathon.commerce.CommerceDtos.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.iare.hackathon.wallet.ProductPaymentGateway;
import com.iare.hackathon.wallet.WalletRepository;
import com.iare.hackathon.phototask.MachineRewardRepository;
import com.iare.hackathon.withdrawal.WithdrawalService;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Validator;

class CommerceBalanceTests {
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PURCHASE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    private record Fixture(CommerceService service, CommerceRepository repository, WalletRepository wallet) {}

    private Fixture fixture() {
        var repository = mock(CommerceRepository.class);
        var wallet = mock(WalletRepository.class);
        var payments = mock(ProductPaymentGateway.class);
        var winnings = mock(WithdrawalService.class);
        var validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Set.of());
        when(repository.transaction(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(repository.existing("alice", PRODUCT)).thenReturn(Optional.empty());
        when(repository.terms(PRODUCT)).thenReturn(new CommerceRepository.Terms(PRODUCT, "Machine A", "/machine.png",
                100_000, 5_000, 45, true, 5000));
        when(repository.intent(eq("alice"), any(), any())).thenReturn(PURCHASE);
        var row = new CommerceRepository.Row(PURCHASE, "alice", "Alice", "alice@example.test", PRODUCT, "Machine A", "/machine.png",
                100_000, 5_000, 45, NOW.minusSeconds(3600), "UTC", LocalTime.MIDNIGHT, null, null, "CREATING", null, null, null, NOW, 0, 0, 0, 5000, Collections.nCopies(45,5000L));
        when(repository.row(PURCHASE, "alice")).thenReturn(row);
        when(repository.referral("alice")).thenReturn(Optional.empty());
        when(wallet.balance("alice")).thenReturn(50_000L);
        var beans = new StaticListableBeanFactory();
        beans.addBean("repository", repository);
        beans.addBean("wallet", wallet);
        beans.addBean("machineRewards",mock(MachineRewardRepository.class));
        var service = new CommerceService(beans.getBeanProvider(CommerceRepository.class), payments,
                beans.getBeanProvider(WalletRepository.class), winnings,
                new CommerceConfiguration.ClaimSchedule(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
                Clock.fixed(NOW, ZoneOffset.UTC), validator,beans.getBeanProvider(MachineRewardRepository.class));
        return new Fixture(service, repository, wallet);
    }

    @Test void purchaseUsesDatabasePriceAndDebitsRechargeBalance() {
        var fixture = fixture();
        when(fixture.wallet.debitForPurchase("alice", PURCHASE, 100_000)).thenReturn(50_000L);
        fixture.service.buy("alice", new Buy(PRODUCT));
        verify(fixture.wallet).debitForPurchase("alice", PURCHASE, 100_000);
        verify(fixture.repository).paidFromWallet(any(), eq(NOW), any(), any());
    }

    @Test void insufficientRechargeBalanceDoesNotFinalizePurchase() {
        var fixture = fixture();
        when(fixture.wallet.debitForPurchase("alice", PURCHASE, 100_000))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Insufficient Recharge Balance"));
        assertThrows(ResponseStatusException.class, () -> fixture.service.buy("alice", new Buy(PRODUCT)));
        verify(fixture.repository, never()).paidFromWallet(any(), any(), any(), any());
    }
}
