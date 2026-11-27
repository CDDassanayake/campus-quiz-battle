package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class ClientTestSupport {
    private ClientTestSupport() { }
    static ClientGameState.Snapshot await(ClientGameState state, Predicate<ClientGameState.Snapshot> predicate) throws Exception {
        CompletableFuture<ClientGameState.Snapshot> found = new CompletableFuture<>();
        Consumer<ClientGameState.Snapshot> observer = s -> { if (predicate.test(s)) { found.complete(s); } };
        state.addObserver(observer);
        try { observer.accept(state.snapshot()); return found.get(4, TimeUnit.SECONDS); }
        finally { state.removeObserver(observer); }
    }
}
