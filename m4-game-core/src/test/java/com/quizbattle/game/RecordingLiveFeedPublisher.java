package com.quizbattle.game;

import com.quizbattle.common.LiveFeedPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe recording {@link LiveFeedPublisher} used by the M4 tests.
 *
 * <p>Events are stored in arrival order and include the publishing thread name so tests
 * can verify the scheduler naming convention.</p>
 */
final class RecordingLiveFeedPublisher implements LiveFeedPublisher {

    record CountdownEvent(String roomId, int secondsLeft, String threadName) {
    }

    record LeaderboardEvent(String roomId, Map<String, Integer> scores, String threadName) {
    }

    private final CopyOnWriteArrayList<CountdownEvent> countdowns = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LeaderboardEvent> leaderboards = new CopyOnWriteArrayList<>();
    private final Object monitor = new Object();

    @Override
    public void publishCountdown(String roomId, int secondsLeft) {
        countdowns.add(new CountdownEvent(roomId, secondsLeft, Thread.currentThread().getName()));
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @Override
    public void publishLeaderboard(String roomId, Map<String, Integer> scores) {
        // Copy so later mutations in the game core cannot change recorded history.
        Map<String, Integer> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        leaderboards.add(new LeaderboardEvent(roomId, snapshot, Thread.currentThread().getName()));
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @Override
    public void close() {
        // Nothing to close.
    }

    List<CountdownEvent> countdowns() {
        return List.copyOf(countdowns);
    }

    List<CountdownEvent> countdownsFor(String roomId) {
        List<CountdownEvent> matching = new ArrayList<>();
        for (CountdownEvent event : countdowns) {
            if (event.roomId().equals(roomId)) {
                matching.add(event);
            }
        }
        return matching;
    }

    List<LeaderboardEvent> leaderboards() {
        return List.copyOf(leaderboards);
    }

    List<LeaderboardEvent> leaderboardsFor(String roomId) {
        List<LeaderboardEvent> matching = new ArrayList<>();
        for (LeaderboardEvent event : leaderboards) {
            if (event.roomId().equals(roomId)) {
                matching.add(event);
            }
        }
        return matching;
    }

    LeaderboardEvent lastLeaderboard(String roomId) {
        List<LeaderboardEvent> matching = leaderboardsFor(roomId);
        return matching.isEmpty() ? null : matching.get(matching.size() - 1);
    }

    boolean awaitCountdown(String roomId, int count, long timeoutMillis) {
        return await(() -> countdownsFor(roomId).size() >= count, timeoutMillis);
    }

    boolean awaitLeaderboard(String roomId, int count, long timeoutMillis) {
        return await(() -> leaderboardsFor(roomId).size() >= count, timeoutMillis);
    }

    /**
     * Waits until the most recent leaderboard for a room matches the predicate. Useful
     * because the final round leaderboard is published just after GAME_OVER is enqueued.
     */
    boolean awaitLeaderboardScores(String roomId,
                                   java.util.function.Predicate<Map<String, Integer>> predicate,
                                   long timeoutMillis) {
        return await(() -> {
            LeaderboardEvent event = lastLeaderboard(roomId);
            return event != null && predicate.test(event.scores());
        }, timeoutMillis);
    }

    private boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (monitor) {
            while (!condition.getAsBoolean()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    monitor.wait(Math.max(1L, remainingNanos / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
