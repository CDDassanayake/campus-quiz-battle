package com.quizbattle.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A room: its players, host, lifecycle state, selected questions, current round,
 * scores and timer handles.
 *
 * <h2>Locking strategy</h2>
 *
 * <p><strong>There is exactly one {@link ReentrantLock} per room.</strong> It guards
 * every mutable field of the room: the player map, the host username, the state, the
 * selected questions, the current round, the completed-round counter, per-player
 * scores/answer flags and the list of scheduled futures.</p>
 *
 * <p>Why one lock per room instead of a single global lock or per-player locks?</p>
 * <ul>
 *   <li>Rooms are independent, so different rooms can transact in parallel. A global
 *       lock would serialise every answer in every room.</li>
 *   <li>Every game rule (join, start, answer, close round) reads or writes several
 *       related fields. A single room lock makes each transition atomic; finer-grained
 *       locks would need a strict ordering and invite deadlocks.</li>
 *   <li>The lock is never held for I/O other than {@link com.quizbattle.common.ClientSession#send},
 *       which is contractual non-blocking enqueueing. That keeps critical sections short
 *       and gives deterministic TCP message order (RESULT is enqueued before the next
 *       QUESTION).</li>
 * </ul>
 *
 * <p>Rules that keep the design deadlock-free:</p>
 * <ol>
 *   <li><strong>Never acquire a second room lock.</strong> {@link RoomManager} resolves
 *       the target room first and locks only that room.</li>
 *   <li><strong>Never call {@code session.close()}</strong> while holding the lock
 *       (only duplicate-username handling closes sessions, and it holds no room lock).</li>
 *   <li><strong>Never call the live-feed publisher</strong> while holding the lock.
 *       Snapshots are copied under the lock and published after unlocking.</li>
 *   <li>Scheduled callbacks never mutate state directly. They submit a task to the
 *       handler executor, which then takes this lock and validates a round token, so a
 *       stale timer cannot touch a removed room or a newer round.</li>
 * </ol>
 */
final class Room {

    private final ReentrantLock lock = new ReentrantLock();
    private final String id;
    private final int idNumber;
    private final String name;
    private final int requestedQuestionCount;
    private final int maxPlayers;

    /** Insertion-ordered so the earliest remaining player can be promoted to host. */
    private final LinkedHashMap<String, PlayerState> players = new LinkedHashMap<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    private String hostUsername;
    private RoomState state = RoomState.LOBBY;
    private List<Question> questions = List.of();
    private Round currentRound;
    private int completedRounds;
    private long lastLeaderboardPublishNanos = Long.MIN_VALUE / 2;
    private boolean removed;

    Room(String id, int idNumber, String name, int requestedQuestionCount, int maxPlayers) {
        this.id = id;
        this.idNumber = idNumber;
        this.name = name;
        this.requestedQuestionCount = requestedQuestionCount;
        this.maxPlayers = maxPlayers;
    }

    // ---------------------------------------------------------------- locking

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    // ---------------------------------------------------------------- players

    void addPlayer(PlayerState player) {
        players.put(player.username(), player);
        if (hostUsername == null) {
            hostUsername = player.username();
        }
    }

    boolean hasPlayer(String username) {
        return players.containsKey(username);
    }

    PlayerState player(String username) {
        return players.get(username);
    }

    int playerCount() {
        return players.size();
    }

    boolean isFull() {
        return players.size() >= maxPlayers;
    }

    boolean isHost(String username) {
        return hostUsername != null && hostUsername.equals(username);
    }

    String hostUsername() {
        return hostUsername;
    }

    /**
     * Removes a player and promotes the earliest remaining player if the host left.
     *
     * @return the removal result, or {@code null} if the player was not in the room
     */
    Removal removePlayer(String username) {
        PlayerState removedPlayer = players.remove(username);
        if (removedPlayer == null) {
            return null;
        }
        String promotedHost = null;
        if (username.equals(hostUsername)) {
            hostUsername = players.isEmpty() ? null : players.keySet().iterator().next();
            promotedHost = hostUsername;
        }
        return new Removal(removedPlayer, promotedHost);
    }

    List<PlayerState> playersSnapshot() {
        return List.copyOf(players.values());
    }

    // ---------------------------------------------------------------- lifecycle

    RoomState state() {
        return state;
    }

    void setState(RoomState state) {
        this.state = state;
    }

    List<Question> questions() {
        return questions;
    }

    int requestedQuestionCount() {
        return requestedQuestionCount;
    }

    int totalRounds() {
        return questions.isEmpty() ? requestedQuestionCount : questions.size();
    }

    void beginGame(List<Question> selectedQuestions) {
        this.questions = List.copyOf(selectedQuestions);
        this.state = RoomState.RUNNING;
        this.completedRounds = 0;
        this.currentRound = null;
        for (PlayerState player : players.values()) {
            player.resetForGame();
        }
    }

    Round currentRound() {
        return currentRound;
    }

    void setCurrentRound(Round round) {
        this.currentRound = round;
    }

    int completedRounds() {
        return completedRounds;
    }

    void setCompletedRounds(int completedRounds) {
        this.completedRounds = completedRounds;
    }

    void resetPlayersForRound() {
        for (PlayerState player : players.values()) {
            player.resetForRound();
        }
    }

    // ---------------------------------------------------------------- scores

    /**
     * Returns an immutable, ordered leaderboard: score descending, then username.
     *
     * @return an immutable map in leaderboard order
     */
    Map<String, Integer> leaderboardSnapshot() {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(players.size());
        for (PlayerState player : players.values()) {
            entries.add(Map.entry(player.username(), player.totalScore()));
        }
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(ordered);
    }

    long lastLeaderboardPublishNanos() {
        return lastLeaderboardPublishNanos;
    }

    void setLastLeaderboardPublishNanos(long nanos) {
        this.lastLeaderboardPublishNanos = nanos;
    }

    // ---------------------------------------------------------------- timers

    void track(ScheduledFuture<?> future) {
        scheduledTasks.add(future);
    }

    /** Cancels every timer owned by this room. Called with the lock held. */
    void cancelScheduledTasks() {
        for (ScheduledFuture<?> future : scheduledTasks) {
            future.cancel(false);
        }
        scheduledTasks.clear();
        if (currentRound != null) {
            currentRound.markClosed();
            currentRound.cancelTimers();
        }
    }

    // ---------------------------------------------------------------- identity

    String id() {
        return id;
    }

    int idNumber() {
        return idNumber;
    }

    String name() {
        return name;
    }

    int maxPlayers() {
        return maxPlayers;
    }

    boolean isRemoved() {
        return removed;
    }

    void markRemoved() {
        this.removed = true;
    }

    /** Result of {@link #removePlayer(String)}. */
    record Removal(PlayerState player, String promotedHost) {
    }
}
