package com.quizbattle.game;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One question round: which question is being asked, when the round started, when it
 * closes, and the timer handles owned by the room.
 *
 * <p><strong>Thread safety:</strong> {@link #index()}, {@link #question()},
 * {@link #startNanos()}, {@link #deadlineNanos()} and {@link #timeLimitSeconds()} are
 * immutable. {@link #isClosed()} and {@link #markClosed()} are atomic so the countdown
 * ticker and the close task can read them without taking the room lock. The timer
 * handles are guarded by the owning {@link Room}'s lock.</p>
 */
final class Round {

    private final int index;
    private final Question question;
    private final int timeLimitSeconds;
    private final long startNanos;
    private final long deadlineNanos;
    private final long durationNanos;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ScheduledFuture<?> ticker;
    private ScheduledFuture<?> closeTask;

    /**
     * @param index            1-based round index
     * @param question         the question
     * @param timeLimitSeconds configured time limit, at least 1
     * @param startNanos       the clock value when the question was broadcast
     */
    Round(int index, Question question, int timeLimitSeconds, long startNanos) {
        if (index < 1) {
            throw new IllegalArgumentException("Round index must be at least 1");
        }
        if (timeLimitSeconds < 1) {
            throw new IllegalArgumentException("Time limit must be at least 1 second");
        }
        this.index = index;
        this.question = question;
        this.timeLimitSeconds = timeLimitSeconds;
        this.startNanos = startNanos;
        this.durationNanos = timeLimitSeconds * 1_000_000_000L;
        this.deadlineNanos = startNanos + durationNanos;
    }

    int index() {
        return index;
    }

    Question question() {
        return question;
    }

    int timeLimitSeconds() {
        return timeLimitSeconds;
    }

    long startNanos() {
        return startNanos;
    }

    long deadlineNanos() {
        return deadlineNanos;
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * Atomically closes the round.
     *
     * @return {@code true} if this call closed it, {@code false} if it was already closed
     */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    /**
     * Computes the points for a correct answer.
     *
     * <p>Formula: {@code 100 + round(50 * secondsRemaining / timeLimit)}. An answer at the
     * deadline receives the base 100 points.</p>
     *
     * @param answeredAtNanos the clock value when the answer was accepted
     * @return points in the range 100..150
     */
    int pointsFor(long answeredAtNanos) {
        long remaining = Math.max(0L, deadlineNanos - answeredAtNanos);
        double fraction = (double) remaining / (double) durationNanos;
        return 100 + (int) Math.round(50.0 * fraction);
    }

    /**
     * Returns the number of whole/partial seconds left, rounded up like a KU countdown.
     *
     * @param nowNanos the current clock value
     * @return seconds in the range 0..timeLimit
     */
    int remainingSeconds(long nowNanos) {
        long remaining = Math.max(0L, deadlineNanos - nowNanos);
        long seconds = (remaining + 999_999_999L) / 1_000_000_000L;
        return (int) Math.min(timeLimitSeconds, seconds);
    }

    void setTicker(ScheduledFuture<?> ticker) {
        this.ticker = ticker;
    }

    void setCloseTask(ScheduledFuture<?> closeTask) {
        this.closeTask = closeTask;
    }

    /** Cancels the ticker and the close task. Called with the room lock held. */
    void cancelTimers() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
        if (closeTask != null) {
            closeTask.cancel(false);
            closeTask = null;
        }
    }
}
