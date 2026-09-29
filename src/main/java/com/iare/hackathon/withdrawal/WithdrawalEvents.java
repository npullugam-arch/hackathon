package com.iare.hackathon.withdrawal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Only invalidation signals leave this channel; financial and bank data use authenticated APIs. */
@Component
public class WithdrawalEvents {
    private final ConcurrentHashMap<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String user) {
        var emitter = new SseEmitter(60000L);
        clients.compute(user, (key, set) -> {
            if (set == null) set = ConcurrentHashMap.newKeySet();
            set.add(emitter);
            return set;
        });
        Runnable remove = () -> clients.computeIfPresent(user, (key, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(error -> remove.run());
        send(emitter);
        return emitter;
    }

    public void changedAfterCommit(String user) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                var emitters = clients.get(user);
                if (emitters != null) emitters.forEach(WithdrawalEvents::send);
            }
        });
    }

    private static void send(SseEmitter emitter) {
        try { emitter.send(SseEmitter.event().name("withdrawal-change").data("refresh")); }
        catch (Exception ex) { emitter.complete(); }
    }
}
