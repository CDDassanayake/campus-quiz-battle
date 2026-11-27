package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.ClientGameState.Snapshot;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Disposable snapshot bridge. Production supplies Platform::runLater; a serial
 * dispatcher can be supplied in headless tests. Queued callbacks check disposal.
 */
public final class UiStateSubscription implements AutoCloseable {
    private final ClientGameState source;
    private final Consumer<Snapshot> observer;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private long deliveredVersion = -1; // Accessed only by the serial UI dispatcher.
    private final java.util.ArrayDeque<Snapshot> queued = new java.util.ArrayDeque<>();

    public UiStateSubscription(ClientGameState source, Consumer<Runnable> dispatcher, Consumer<Snapshot> target) {
        this(source, dispatcher, target, false);
    }

    /** Coalesces only adjacent informational updates; retains every gameplay/navigation boundary. */
    public UiStateSubscription(ClientGameState source, Consumer<Runnable> dispatcher, Consumer<Snapshot> target, boolean coalesceFeed) {
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(dispatcher); Objects.requireNonNull(target);
        java.util.ArrayDeque<Snapshot> queue = queued;
        java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable drain = new Runnable() {
            @Override public void run() {
                // Yield between batches so a busy producer cannot monopolize the FX thread.
                try {
                    for (int i = 0; i < 64; i++) {
                        Snapshot next;
                        synchronized (queue) {
                            if (!active.get()) { queue.clear(); return; }
                            next = queue.poll();
                            if (next == null) { return; }
                        }
                        if (active.get() && next.version() > deliveredVersion) { deliveredVersion = next.version(); target.accept(next); }
                    }
                } finally {
                    boolean more;
                    synchronized (queue) { scheduled.set(false); more = active.get() && !queue.isEmpty(); }
                    if (more && scheduled.compareAndSet(false, true)) { dispatcher.accept(this); }
                }
            }
        };
        observer = snapshot -> {
            if (active.get()) {
                if (coalesceFeed) {
                    synchronized (queue) {
                        if (!active.get()) { return; }
                        Snapshot tail = queue.peekLast();
                        if (tail != null && snapshot.version() <= tail.version()) { return; }
                        if (tail != null && onlyFeedChanged(tail, snapshot)) { queue.removeLast(); }
                        queue.add(snapshot);
                    }
                    if (scheduled.compareAndSet(false, true)) { dispatcher.accept(drain); }
                    return;
                }
                dispatcher.accept(() -> {
                    if (active.get() && snapshot.version() > deliveredVersion) {
                        deliveredVersion = snapshot.version(); target.accept(snapshot);
                    }
                });
            }
        };
        source.addObserver(observer);
        observer.accept(source.snapshot());
    }
    private static boolean onlyFeedChanged(Snapshot a, Snapshot b) {
        return a.status() == b.status() && a.question() == b.question() && a.roundResult() == b.roundResult()
                && a.finalResult() == b.finalResult() && a.connected() == b.connected() && a.authenticated() == b.authenticated()
                && Objects.equals(a.username(), b.username()) && Objects.equals(a.serverHost(), b.serverHost()) && a.serverPort() == b.serverPort()
                && Objects.equals(a.roomId(), b.roomId()) && Objects.equals(a.roomName(), b.roomName()) && a.host() == b.host()
                && a.knownPlayers().equals(b.knownPlayers()) && a.availableRooms().equals(b.availableRooms())
                && Objects.equals(a.selectedAnswer(), b.selectedAnswer()) && a.answerSubmitted() == b.answerSubmitted()
                && a.totalScore() == b.totalScore() && Objects.equals(a.notice(), b.notice()) && Objects.equals(a.errorCode(), b.errorCode())
                && Objects.equals(a.pendingAction(), b.pendingAction()) && Objects.equals(a.lastPong(), b.lastPong());
    }
    @Override public void close() {
        if (active.compareAndSet(true, false)) {
            source.removeObserver(observer); synchronized (queued) { queued.clear(); }
        }
    }
}
