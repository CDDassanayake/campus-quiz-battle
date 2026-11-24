package com.quizbattle.game;

import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ClientSession;
import com.quizbattle.common.LiveFeedPublisher;
import com.quizbattle.common.Message;
import com.quizbattle.common.MessageHandler;
import com.quizbattle.common.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The multiplayer game core: rooms, rounds, timers and scoring.
 *
 * <p>{@code RoomManager} implements the frozen {@link MessageHandler} contract, so the
 * NIO gateway hands it every decrypted game message. All game logic runs on the shared
 * handler pool created here; the gateway obtains it through {@link #executor()}.</p>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li><strong>Handler pool</strong> ({@code game.handler.pool.size}, default 8):
 *       executes {@code onConnect}/{@code onMessage}/{@code onDisconnect} and every
 *       game task, including round closure.</li>
 *   <li><strong>Scheduler</strong> (two daemon threads): only fires timers. A timer
 *       never mutates game state; it submits a task to the handler pool. Countdown
 *       publishing reads immutable round timing data and never takes a room lock.</li>
 *   <li><strong>Room lock</strong>: one {@link java.util.concurrent.locks.ReentrantLock}
 *       per room guards all room state (see the class comment on {@link Room}).</li>
 * </ul>
 *
 * <h2>Score</h2>
 * <p>A correct answer scores {@code 100 + round(50 * secondsRemaining / timeLimit)};
 * a wrong answer scores 0. Only the first answer per player per round is accepted.</p>
 */
public final class RoomManager implements MessageHandler, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RoomManager.class.getName());

    private static final int DEFAULT_POOL_SIZE = 8;
    private static final int DEFAULT_QUESTION_TIME_SECONDS = 15;
    private static final int DEFAULT_MAX_PLAYERS = 10;
    private static final int DEFAULT_ROUND_GAP_MILLIS = 3_000;
    private static final int DEFAULT_LEADERBOARD_THROTTLE_MILLIS = 250;
    private static final String DEFAULT_QUESTIONS_FILE = "questions/sample-questions.csv";
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private static final Pattern ROOM_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_ ]{1,24}");

    private final LiveFeedPublisher publisher;
    private final int questionTimeSeconds;
    private final int maxPlayers;
    private final long roundGapMillis;
    private final long leaderboardThrottleNanos;
    private final LongSupplier clock;
    private final QuestionBank questionBank;

    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor scheduler;

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientSession> sessionsByUsername = new ConcurrentHashMap<>();
    private final AtomicInteger roomIdSequence = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a manager using production settings from {@link AppConfig}.
     *
     * <p>Questions are loaded eagerly, before the gateway starts accepting clients.
     * A URL source falls back to the local CSV on any failure. If loading fails
     * completely the manager still starts, but room creation is rejected with
     * {@code BAD_MESSAGE} instead of failing the server process.</p>
     *
     * @param config    configuration (may be {@code null}, defaults apply)
     * @param publisher live-feed publisher (M3); must not be {@code null}
     */
    public RoomManager(AppConfig config, LiveFeedPublisher publisher) {
        this(config, publisher, buildQuestionSource(config), System::nanoTime);
    }

    /**
     * Test seam: allows a fixed question source and a controllable clock for
     * deterministic scoring.
     */
    RoomManager(AppConfig config, LiveFeedPublisher publisher, QuestionSource questionSource, LongSupplier clock) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(questionSource, "questionSource");

        this.questionTimeSeconds = clamp(
                config == null ? DEFAULT_QUESTION_TIME_SECONDS
                        : config.getInt("game.question.time.seconds", DEFAULT_QUESTION_TIME_SECONDS),
                1, 3_600);
        this.maxPlayers = clamp(
                config == null ? DEFAULT_MAX_PLAYERS
                        : config.getInt("game.room.max.players", DEFAULT_MAX_PLAYERS),
                1, 1_000);
        this.roundGapMillis = clamp(
                config == null ? DEFAULT_ROUND_GAP_MILLIS
                        : config.getInt("game.round.gap.millis", DEFAULT_ROUND_GAP_MILLIS),
                0, 60_000);
        // Hard floor of 250 ms => never more than four leaderboard datagrams per second
        // per room, even if the optional configuration value is set lower.
        long configuredThrottleMillis = config == null ? DEFAULT_LEADERBOARD_THROTTLE_MILLIS
                : config.getInt("game.leaderboard.throttle.millis", DEFAULT_LEADERBOARD_THROTTLE_MILLIS);
        this.leaderboardThrottleNanos = Math.max(250L, Math.max(0L, configuredThrottleMillis)) * 1_000_000L;

        int poolSize = clamp(
                config == null ? DEFAULT_POOL_SIZE
                        : config.getInt("game.handler.pool.size", DEFAULT_POOL_SIZE),
                1, 64);
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                namedThreadFactory("m4-handler"));
        this.executor.allowCoreThreadTimeOut(false);

        this.scheduler = new ScheduledThreadPoolExecutor(2, namedThreadFactory("m4-scheduler"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.questionBank = loadQuestionBank(questionSource);
        if (this.questionBank == null) {
            LOG.warning("No usable question bank was loaded; CREATE_ROOM will be rejected");
        } else {
            LOG.info(() -> "Loaded " + this.questionBank.size() + " questions");
        }
    }

    // ==================================================================
    // MessageHandler
    // ==================================================================

    @Override
    public void onConnect(ClientSession session) {
        Objects.requireNonNull(session, "session");
        if (closed.get()) {
            session.close();
            return;
        }
        String username = session.username().orElse(null);
        if (username == null || username.isBlank()) {
            // The gateway promises a username after HELLO; treat anything else as broken.
            LOG.warning("onConnect called without an authenticated username; closing session");
            session.close();
            return;
        }

        ClientSession previous = sessionsByUsername.putIfAbsent(username, session);
        if (previous != null && previous != session) {
            LOG.log(Level.INFO, "Rejecting duplicate connection for {0}", username);
            session.send(error("ALREADY_CONNECTED", "username is already connected"));
            // Never close under a room lock; none is held here.
            session.close();
            return;
        }
        LOG.log(Level.INFO, () -> "Player connected: " + username + " from " + session.remoteAddress());
    }

    @Override
    public void onMessage(ClientSession session, Message message) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(message, "message");
        if (closed.get()) {
            return;
        }
        String username = session.username().orElse(null);
        if (username == null) {
            return;
        }
        // Ignore late messages from a session that has already been replaced/disconnected.
        if (sessionsByUsername.get(username) != session) {
            return;
        }

        try {
            switch (message.type()) {
                case LIST_ROOMS -> handleListRooms(session);
                case CREATE_ROOM -> handleCreateRoom(session, username, message);
                case JOIN -> handleJoin(session, username, message);
                case LEAVE -> handleLeave(session, username, message);
                case START -> handleStart(session, username, message);
                case ANSWER -> handleAnswer(session, username, message);
                default -> session.send(error("BAD_MESSAGE",
                        "message type not handled by the game core: " + message.type()));
            }
        } catch (RuntimeException e) {
            // A malformed or unexpected message must never kill the handler thread.
            LOG.log(Level.WARNING,
                    "Failed to handle " + message.type() + " from " + username, e);
            session.send(error("BAD_MESSAGE", "message could not be processed"));
        }
    }

    @Override
    public void onDisconnect(ClientSession session) {
        Objects.requireNonNull(session, "session");
        String username = session.username().orElse(null);
        if (username == null) {
            return;
        }
        // Only the session that owns this username may release it. A rejected duplicate
        // connection also produces onDisconnect (after ERROR|ALREADY_CONNECTED), and it
        // must not evict the player who actually owns the username from their room.
        if (!sessionsByUsername.remove(username, session)) {
            LOG.log(Level.FINE, "Ignoring disconnect from an unregistered session for {0}", username);
            return;
        }

        Room room = findRoomOf(username);
        if (room == null) {
            LOG.log(Level.INFO, "Player disconnected: {0}", username);
            return;
        }

        boolean roomRemoved = false;
        Map<String, Integer> leaderboard = null;
        room.lock();
        try {
            if (room.isRemoved() || !room.hasPlayer(username)) {
                return;
            }
            Room.Removal removal = room.removePlayer(username);
            if (room.playerCount() == 0) {
                room.markRemoved();
                room.cancelScheduledTasks();
                rooms.remove(room.id(), room);
                roomRemoved = true;
            } else {
                Message left = new Message(MessageType.PLAYER_LEFT, List.of(username));
                for (PlayerState player : room.playersSnapshot()) {
                    player.session().send(left);
                }
                if (removal != null && removal.promotedHost() != null) {
                    PlayerState promoted = room.player(removal.promotedHost());
                    if (promoted != null) {
                        promoted.session().send(hostUpdate(room));
                    }
                }
                leaderboard = room.leaderboardSnapshot();
            }
        } finally {
            room.unlock();
        }

        LOG.log(Level.INFO, "Player disconnected: {0}", username);
        if (!roomRemoved) {
            if (leaderboard != null) {
                safePublishLeaderboard(room.id(), leaderboard);
            }
            maybeCloseEarly(room);
        }
    }

    // ==================================================================
    // Command handlers
    // ==================================================================

    private void handleListRooms(ClientSession session) {
        String payload = rooms.values().stream()
                .sorted(Comparator.comparingInt(Room::idNumber))
                .map(this::describeRoom)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        session.send(new Message(MessageType.ROOMS, List.of(payload)));
    }

    private String describeRoom(Room room) {
        room.lock();
        try {
            if (room.isRemoved()) {
                return null;
            }
            return room.id() + ":" + room.name() + ":" + room.playerCount() + ":" + room.state().name();
        } finally {
            room.unlock();
        }
    }

    private void handleCreateRoom(ClientSession session, String username, Message message) {
        if (message.fieldCount() != 2) {
            session.send(error("BAD_MESSAGE", "CREATE_ROOM expects roomName and questionCount"));
            return;
        }
        String roomName = message.getField(0).trim();
        if (!ROOM_NAME_PATTERN.matcher(roomName).matches()) {
            session.send(error("BAD_MESSAGE", "invalid room name (use 1-24 letters, digits, spaces or _)"));
            return;
        }
        int requestedQuestions;
        try {
            requestedQuestions = Integer.parseInt(message.getField(1).trim());
        } catch (NumberFormatException e) {
            session.send(error("BAD_MESSAGE", "questionCount must be a number"));
            return;
        }
        if (requestedQuestions < 1) {
            session.send(error("BAD_MESSAGE", "questionCount must be at least 1"));
            return;
        }
        if (questionBank == null) {
            session.send(error("BAD_MESSAGE", "no questions are available on this server"));
            return;
        }
        if (findRoomOf(username) != null) {
            session.send(error("BAD_MESSAGE", "already in a room; leave it first"));
            return;
        }

        int questionCount = Math.min(requestedQuestions, questionBank.size());
        int idNumber = roomIdSequence.incrementAndGet();
        Room room = new Room("r" + idNumber, idNumber, roomName, questionCount, maxPlayers);
        room.lock();
        try {
            room.addPlayer(new PlayerState(username, session));
        } finally {
            room.unlock();
        }
        rooms.put(room.id(), room);

        LOG.log(Level.INFO, "Room {0} created by {1}", new Object[]{room.id(), username});
        session.send(new Message(MessageType.JOINED,
                List.of(room.id(), room.name(), "true")));
    }

    private void handleJoin(ClientSession session, String username, Message message) {
        if (message.fieldCount() != 1) {
            session.send(error("BAD_MESSAGE", "JOIN expects a roomId"));
            return;
        }
        String roomId = message.getField(0).trim();
        Room room = rooms.get(roomId);
        if (room == null) {
            session.send(error("ROOM_NOT_FOUND", "no such room"));
            return;
        }

        Room currentRoom = findRoomOf(username);
        if (currentRoom == room) {
            // Idempotent JOIN: re-send the join confirmation instead of failing.
            room.lock();
            try {
                if (room.isRemoved()) {
                    session.send(error("ROOM_NOT_FOUND", "no such room"));
                } else {
                    session.send(new Message(MessageType.JOINED,
                            List.of(room.id(), room.name(), String.valueOf(room.isHost(username)))));
                }
            } finally {
                room.unlock();
            }
            return;
        }
        if (currentRoom != null) {
            session.send(error("BAD_MESSAGE", "already in another room; leave it first"));
            return;
        }

        room.lock();
        try {
            if (room.isRemoved()) {
                session.send(error("ROOM_NOT_FOUND", "no such room"));
                return;
            }
            if (room.state() != RoomState.LOBBY) {
                session.send(error("GAME_RUNNING", "the game has already started"));
                return;
            }
            if (room.isFull()) {
                session.send(error("ROOM_FULL", "the room is full"));
                return;
            }
            room.addPlayer(new PlayerState(username, session));
            // Send under the lock so JOINED is ordered before any QUESTION from a
            // concurrent START; ClientSession.send only enqueues.
            session.send(new Message(MessageType.JOINED,
                    List.of(room.id(), room.name(), "false")));
            Message playerJoined = new Message(MessageType.PLAYER_JOINED, List.of(username));
            for (PlayerState player : room.playersSnapshot()) {
                if (!player.username().equals(username)) {
                    player.session().send(playerJoined);
                }
            }
            LOG.log(Level.INFO, "Player {0} joined room {1}", new Object[]{username, room.id()});
        } finally {
            room.unlock();
        }
    }

    private void handleLeave(ClientSession session, String username, Message message) {
        if (message.fieldCount() != 0) {
            session.send(error("BAD_MESSAGE", "LEAVE takes no fields"));
            return;
        }
        Room room = findRoomOf(username);
        if (room == null) {
            session.send(error("NOT_IN_ROOM", "you are not in a room"));
            return;
        }

        boolean roomRemoved = false;
        Map<String, Integer> leaderboard = null;
        room.lock();
        try {
            if (room.isRemoved() || !room.hasPlayer(username)) {
                session.send(error("NOT_IN_ROOM", "you are not in a room"));
                return;
            }
            Room.Removal removal = room.removePlayer(username);
            if (room.playerCount() == 0) {
                room.markRemoved();
                room.cancelScheduledTasks();
                rooms.remove(room.id(), room);
                roomRemoved = true;
            } else {
                Message left = new Message(MessageType.PLAYER_LEFT, List.of(username));
                for (PlayerState player : room.playersSnapshot()) {
                    player.session().send(left);
                }
                // RoomManager promotes the earliest remaining player (Room keeps
                // insertion order). The promoted player is told with a fresh JOINED
                // message carrying isHost=true. Flagged for M2 as a protocol note.
                if (removal != null && removal.promotedHost() != null) {
                    PlayerState promoted = room.player(removal.promotedHost());
                    if (promoted != null) {
                        promoted.session().send(hostUpdate(room));
                    }
                }
                leaderboard = room.leaderboardSnapshot();
            }
        } finally {
            room.unlock();
        }

        if (!roomRemoved) {
            if (leaderboard != null) {
                safePublishLeaderboard(room.id(), leaderboard);
            }
            maybeCloseEarly(room);
        }
    }

    private void handleStart(ClientSession session, String username, Message message) {
        if (message.fieldCount() != 0) {
            session.send(error("BAD_MESSAGE", "START takes no fields"));
            return;
        }
        Room room = findRoomOf(username);
        if (room == null) {
            session.send(error("NOT_IN_ROOM", "you are not in a room"));
            return;
        }

        room.lock();
        try {
            if (room.isRemoved()) {
                session.send(error("NOT_IN_ROOM", "you are not in a room"));
                return;
            }
            if (!room.isHost(username)) {
                session.send(error("NOT_HOST", "only the host can start the game"));
                return;
            }
            if (room.state() != RoomState.LOBBY) {
                session.send(error("GAME_RUNNING", "the game has already started"));
                return;
            }
            if (room.playerCount() == 0) {
                session.send(error("BAD_MESSAGE", "cannot start an empty room"));
                return;
            }
            if (questionBank == null) {
                session.send(error("BAD_MESSAGE", "no questions are available on this server"));
                return;
            }
            room.beginGame(questionBank.pick(room.requestedQuestionCount()));
            LOG.log(Level.INFO, "Room {0} started by {1} ({2} rounds)",
                    new Object[]{room.id(), username, room.totalRounds()});
        } finally {
            room.unlock();
        }
        // Already on the handler pool; begin the first round immediately.
        startNextRound(room.id());
    }

    private void handleAnswer(ClientSession session, String username, Message message) {
        if (message.fieldCount() != 2) {
            session.send(error("BAD_MESSAGE", "ANSWER expects questionId and choiceIndex"));
            return;
        }
        String questionId = message.getField(0).trim();
        int choice;
        try {
            choice = Integer.parseInt(message.getField(1).trim());
        } catch (NumberFormatException e) {
            session.send(error("BAD_MESSAGE", "choiceIndex must be a number"));
            return;
        }
        if (choice < 0 || choice >= Question.OPTION_COUNT) {
            session.send(error("BAD_MESSAGE", "choiceIndex must be between 0 and 3"));
            return;
        }

        Room room = findRoomOf(username);
        if (room == null) {
            session.send(error("NOT_IN_ROOM", "you are not in a room"));
            return;
        }

        room.lock();
        try {
            if (room.isRemoved()) {
                session.send(error("NOT_IN_ROOM", "you are not in a room"));
                return;
            }
            PlayerState player = room.player(username);
            if (player == null) {
                session.send(error("NOT_IN_ROOM", "you are not in a room"));
                return;
            }
            if (room.state() == RoomState.LOBBY) {
                session.send(error("BAD_MESSAGE", "the game has not started"));
                return;
            }
            Round round = room.currentRound();
            if (room.state() == RoomState.FINISHED || round == null || round.isClosed()) {
                session.send(error("TOO_LATE", "the round is already closed"));
                return;
            }
            if (!String.valueOf(round.question().id()).equals(questionId)) {
                session.send(error("BAD_MESSAGE", "questionId does not match the current question"));
                return;
            }
            if (player.hasAnsweredThisRound()) {
                session.send(error("ALREADY_ANSWERED", "only the first answer counts"));
                return;
            }

            long now = clock.getAsLong();
            if (now > round.deadlineNanos()) {
                session.send(error("TOO_LATE", "the answer arrived after the deadline"));
                return;
            }

            int points = round.question().correctIndex() == choice ? round.pointsFor(now) : 0;
            player.recordAnswer(points);
            LOG.log(Level.FINE, "Answer from {0} in {1}: choice={2}, points={3}",
                    new Object[]{username, room.id(), choice, points});
        } finally {
            room.unlock();
        }

        maybePublishLeaderboard(room);
        maybeCloseEarly(room);
    }

    // ==================================================================
    // Round engine
    // ==================================================================

    /**
     * Broadcasts the next question and schedules its ticker and close task.
     * Runs on the handler pool.
     */
    private void startNextRound(String roomId) {
        if (closed.get()) {
            return;
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        Round round;
        room.lock();
        try {
            if (room.isRemoved() || room.state() != RoomState.RUNNING) {
                return;
            }
            int nextIndex = room.completedRounds() + 1;
            if (nextIndex > room.totalRounds()) {
                return;
            }
            Question question = room.questions().get(nextIndex - 1);
            room.resetPlayersForRound();
            round = new Round(nextIndex, question, questionTimeSeconds, clock.getAsLong());
            room.setCurrentRound(round);

            Message questionMessage = new Message(MessageType.QUESTION, List.of(
                    String.valueOf(question.id()),
                    String.valueOf(nextIndex),
                    String.valueOf(room.totalRounds()),
                    question.text(),
                    question.options().get(0),
                    question.options().get(1),
                    question.options().get(2),
                    question.options().get(3),
                    String.valueOf(round.timeLimitSeconds())));
            for (PlayerState player : room.playersSnapshot()) {
                player.session().send(questionMessage);
            }
            LOG.log(Level.INFO, "Room {0} round {1}/{2} started",
                    new Object[]{room.id(), nextIndex, room.totalRounds()});
        } finally {
            room.unlock();
        }
        scheduleRoundTimers(room, round);
    }

    /**
     * Schedules the once-per-second countdown and the automatic close. Scheduler
     * callbacks never mutate state: the ticker only publishes feed data, and the close
     * callback submits a handler task carrying a round token.
     */
    private void scheduleRoundTimers(Room room, Round round) {
        String roomId = room.id();
        ScheduledFuture<?> ticker = null;
        try {
            ticker = scheduler.scheduleAtFixedRate(() -> {
                if (closed.get() || round.isClosed()) {
                    return;
                }
                safePublishCountdown(roomId, round.remainingSeconds(clock.getAsLong()));
            }, 0L, 1L, TimeUnit.SECONDS);

            ScheduledFuture<?> closeTask = scheduler.schedule(
                    () -> executeGameTask(() -> closeRound(roomId, round.index())),
                    round.timeLimitSeconds(), TimeUnit.SECONDS);

            ScheduledFuture<?> scheduledTicker = ticker;
            room.lock();
            try {
                boolean stale = room.isRemoved()
                        || room.state() != RoomState.RUNNING
                        || room.currentRound() != round
                        || round.isClosed();
                if (stale) {
                    scheduledTicker.cancel(false);
                    closeTask.cancel(false);
                } else {
                    round.setTicker(scheduledTicker);
                    round.setCloseTask(closeTask);
                    room.track(scheduledTicker);
                    room.track(closeTask);
                }
            } finally {
                room.unlock();
            }
        } catch (RejectedExecutionException e) {
            if (ticker != null) {
                ticker.cancel(false);
            }
            LOG.fine("Round timers rejected because the manager is shutting down");
        }
    }

    private void scheduleNextRound(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> executeGameTask(() -> startNextRound(roomId)),
                    roundGapMillis, TimeUnit.MILLISECONDS);
            room.lock();
            try {
                if (room.isRemoved() || room.state() != RoomState.RUNNING) {
                    future.cancel(false);
                } else {
                    room.track(future);
                }
            } finally {
                room.unlock();
            }
        } catch (RejectedExecutionException e) {
            LOG.fine("Next-round timer rejected because the manager is shutting down");
        }
    }

    /**
     * Closes a round. Called only through the handler pool. The round index acts as a
     * token so a stale close task cannot close a newer round or a removed room.
     */
    private void closeRound(String roomId, int roundIndex) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        boolean finished;
        Map<String, Integer> leaderboard;
        room.lock();
        try {
            if (room.isRemoved() || room.state() != RoomState.RUNNING) {
                return;
            }
            Round round = room.currentRound();
            if (round == null || round.index() != roundIndex || !round.markClosed()) {
                return;
            }
            round.cancelTimers();

            List<PlayerState> players = room.playersSnapshot();
            Question question = round.question();
            for (PlayerState player : players) {
                player.session().send(new Message(MessageType.RESULT, List.of(
                        String.valueOf(question.id()),
                        String.valueOf(question.correctIndex()),
                        String.valueOf(player.pointsThisRound()),
                        String.valueOf(player.totalScore()))));
            }

            room.setCompletedRounds(round.index());
            finished = room.completedRounds() >= room.totalRounds();
            leaderboard = room.leaderboardSnapshot();

            if (finished) {
                room.setState(RoomState.FINISHED);
                String standings = standings(leaderboard);
                String winner = leaderboard.isEmpty() ? "" : leaderboard.keySet().iterator().next();
                Message gameOver = new Message(MessageType.GAME_OVER, List.of(winner, standings));
                for (PlayerState player : players) {
                    player.session().send(gameOver);
                }
                LOG.log(Level.INFO, "Room {0} finished; winner {1}", new Object[]{room.id(), winner});
            }
            room.setLastLeaderboardPublishNanos(System.nanoTime());
        } finally {
            room.unlock();
        }

        safePublishLeaderboard(roomId, leaderboard);
        if (!finished) {
            scheduleNextRound(roomId);
        }
    }

    /**
     * Requests an immediate close if every remaining player has answered. This is an
     * intentional UX optimisation (standard quiz behaviour) on top of the timer.
     */
    private void maybeCloseEarly(Room room) {
        Round round;
        room.lock();
        try {
            if (room.isRemoved() || room.state() != RoomState.RUNNING) {
                return;
            }
            round = room.currentRound();
            if (round == null || round.isClosed()) {
                return;
            }
            List<PlayerState> players = room.playersSnapshot();
            if (players.isEmpty()) {
                return;
            }
            for (PlayerState player : players) {
                if (!player.hasAnsweredThisRound()) {
                    return;
                }
            }
        } finally {
            room.unlock();
        }
        executeGameTask(() -> closeRound(room.id(), round.index()));
    }

    // ==================================================================
    // Live feed
    // ==================================================================

    private void maybePublishLeaderboard(Room room) {
        Map<String, Integer> snapshot = null;
        room.lock();
        try {
            if (room.isRemoved()) {
                return;
            }
            long now = System.nanoTime();
            if (now - room.lastLeaderboardPublishNanos() >= leaderboardThrottleNanos) {
                room.setLastLeaderboardPublishNanos(now);
                snapshot = room.leaderboardSnapshot();
            }
        } finally {
            room.unlock();
        }
        if (snapshot != null) {
            safePublishLeaderboard(room.id(), snapshot);
        }
    }

    private void safePublishCountdown(String roomId, int secondsLeft) {
        try {
            publisher.publishCountdown(roomId, secondsLeft);
        } catch (RuntimeException e) {
            // A broken UDP feed must never break the game.
            LOG.log(Level.WARNING, "Live feed countdown publish failed: {0}", e.getClass().getSimpleName());
        }
    }

    private void safePublishLeaderboard(String roomId, Map<String, Integer> scores) {
        try {
            publisher.publishLeaderboard(roomId, scores);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Live feed leaderboard publish failed: {0}", e.getClass().getSimpleName());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Returns the room containing the user, or {@code null}. Never call under a room lock. */
    private Room findRoomOf(String username) {
        for (Room room : rooms.values()) {
            room.lock();
            try {
                if (!room.isRemoved() && room.hasPlayer(username)) {
                    return room;
                }
            } finally {
                room.unlock();
            }
        }
        return null;
    }

    private void executeGameTask(Runnable task) {
        if (closed.get()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Uncaught failure in a game task", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.fine("Game task rejected because the manager is shutting down");
        }
    }

    private static Message error(String code, String text) {
        return new Message(MessageType.ERROR, List.of(code, text));
    }

    private static Message hostUpdate(Room room) {
        return new Message(MessageType.JOINED, List.of(room.id(), room.name(), "true"));
    }

    private static String standings(Map<String, Integer> leaderboard) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : leaderboard.entrySet()) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return builder.toString();
    }

    private static QuestionSource buildQuestionSource(AppConfig config) {
        String file = config == null
                ? DEFAULT_QUESTIONS_FILE
                : config.getString("game.questions.file", DEFAULT_QUESTIONS_FILE);
        LocalQuestionSource local = new LocalQuestionSource(file);
        String url = config == null ? "" : config.getString("game.questions.url", "");
        return url == null || url.isBlank() ? local : new UrlQuestionSource(url, local);
    }

    private static QuestionBank loadQuestionBank(QuestionSource source) {
        try {
            List<Question> questions = source.load();
            if (questions == null || questions.isEmpty()) {
                LOG.warning("Question source returned no questions; games cannot start");
                return null;
            }
            return new QuestionBank(new ArrayList<>(questions));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Could not load questions (" + e.getClass().getSimpleName() + "); games cannot start", e);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failedThread, failure) ->
                    LOG.log(Level.SEVERE, "Uncaught exception in " + failedThread.getName(), failure));
            return thread;
        };
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    /**
     * Returns the shared handler pool. The NIO gateway must use this executor for every
     * {@link MessageHandler} callback so all game logic runs on the M4 threads.
     *
     * @return the handler executor
     */
    public ExecutorService executor() {
        return executor;
    }

    /** Package-private for tests and the simulation. */
    int roomCount() {
        return rooms.size();
    }

    /**
     * Stops the game core: cancels timers, removes rooms, shuts down both pools and
     * waits for running tasks. Idempotent. The live-feed publisher is owned by the
     * application and is not closed here.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down RoomManager");

        for (Room room : rooms.values()) {
            room.lock();
            try {
                room.markRemoved();
                room.cancelScheduledTasks();
            } finally {
                room.unlock();
            }
        }
        rooms.clear();
        sessionsByUsername.clear();

        scheduler.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warning("Handler pool did not terminate in time; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("RoomManager stopped");
    }
}
