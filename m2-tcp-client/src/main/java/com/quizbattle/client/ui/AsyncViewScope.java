package com.quizbattle.client.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One in-flight view action. Disposing a view invalidates queued completions. */
public final class AsyncViewScope implements AutoCloseable {
    private final Consumer<Runnable> dispatch;
    private final Consumer<Boolean> busyChanged;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    public AsyncViewScope(Consumer<Runnable> dispatch, Consumer<Boolean> busyChanged) {
        this.dispatch = Objects.requireNonNull(dispatch); this.busyChanged = Objects.requireNonNull(busyChanged);
    }
    public boolean busy() { return busy.get(); }
    public <T> boolean run(Supplier<CompletableFuture<T>> request, Duration timeout, Consumer<T> success, Consumer<Throwable> failure) {
        if (closed.get() || !busy.compareAndSet(false, true)) { return false; }
        busyChanged.accept(true);
        CompletableFuture<T> operation;
        try { operation = request.get().thenApply(value -> value).orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (RuntimeException e) { operation = CompletableFuture.failedFuture(e); }
        operation.whenComplete((value, error) -> dispatch.accept(() -> {
            if (closed.get()) { return; }
            busy.set(false); busyChanged.accept(false);
            if (error == null) { success.accept(value); } else { failure.accept(error); }
        }));
        return true;
    }
    @Override public void close() { closed.set(true); busy.set(false); }
}
