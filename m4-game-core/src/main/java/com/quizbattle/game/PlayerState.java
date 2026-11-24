package com.quizbattle.game;

import com.quizbattle.common.ClientSession;

import java.util.Objects;

/**
 * Mutable per-player state inside a room.
 *
 * <p><strong>Thread safety:</strong> every field is guarded by the owning
 * {@link Room}'s {@code ReentrantLock}. This class deliberately exposes no locking of
 * its own: the room lock is the single lock for the whole room, which keeps state
 * transitions atomic and avoids nested locking.</p>
 */
final class PlayerState {

    private final String username;
    private final ClientSession session;

    private int totalScore;
    private int pointsThisRound;
    private boolean answeredThisRound;

    PlayerState(String username, ClientSession session) {
        this.username = Objects.requireNonNull(username, "username");
        this.session = Objects.requireNonNull(session, "session");
    }

    String username() {
        return username;
    }

    ClientSession session() {
        return session;
    }

    int totalScore() {
        return totalScore;
    }

    int pointsThisRound() {
        return pointsThisRound;
    }

    boolean hasAnsweredThisRound() {
        return answeredThisRound;
    }

    /**
     * Records the first answer of this player for the current round.
     *
     * @param points the points to award
     */
    void recordAnswer(int points) {
        if (answeredThisRound) {
            throw new IllegalStateException("Player " + username + " already answered this round");
        }
        pointsThisRound = points;
        totalScore += points;
        answeredThisRound = true;
    }

    /** Clears per-round state at the start of a new game. */
    void resetForGame() {
        totalScore = 0;
        resetForRound();
    }

    /** Clears per-round state at the start of a new question. */
    void resetForRound() {
        pointsThisRound = 0;
        answeredThisRound = false;
    }
}
