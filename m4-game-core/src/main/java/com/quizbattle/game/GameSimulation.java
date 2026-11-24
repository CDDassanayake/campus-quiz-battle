package com.quizbattle.game;

import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ClientSession;
import com.quizbattle.common.LiveFeedPublisher;
import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone M4 demonstration: two independent rooms play at the same time without
 * M1, M2, M3 or M5.
 *
 * <p>The simulation drives {@link RoomManager} through the same {@link MessageHandler}
 * API the NIO gateway uses, with fake {@link ClientSession}s that answer questions.
 * It prints handler/scheduler thread names, countdown ticks, leaderboard updates and
 * final standings.</p>
 *
 * <pre>
 * mvn -q -DskipTests install
 * mvn -q -pl m4-game-core exec:java
 * </pre>
 */
public final class GameSimulation {

    private static final int QUESTIONS_PER_ROOM = 2;

    private GameSimulation() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Campus Quiz Battle - M4 Game Core simulation ===");
        System.out.println("Main thread: " + Thread.currentThread().getName());

        AppConfig config = simulationConfig();
        PrintingPublisher publisher = new PrintingPublisher();
        RoomManager manager = new RoomManager(config, publisher);
        ScheduledExecutorService answerScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("m4-sim-answer"));

        try {
            List<SimulationSession> roomA = createRoom(manager, answerScheduler,
                    "Algorithms", "alice", "bob", "carol", "dave");
            List<SimulationSession> roomB = createRoom(manager, answerScheduler,
                    "Networks", "erin", "frank", "grace");

            printHandlerThreads(manager);

            System.out.println();
            System.out.println("--- starting room A (Algorithms) and room B (Networks) ---");
            manager.onMessage(roomA.get(0), new Message(MessageType.START, List.of()));
            manager.onMessage(roomB.get(0), new Message(MessageType.START, List.of()));

            boolean finishedA = roomA.get(0).awaitGameOver(60, TimeUnit.SECONDS);
            boolean finishedB = roomB.get(0).awaitGameOver(60, TimeUnit.SECONDS);
            System.out.println();
            System.out.println("Room A finished: " + finishedA + ", room B finished: " + finishedB);

            System.out.println();
            System.out.println("--- final leaderboards (published to the live feed) ---");
            printLeaderboards(publisher);
            System.out.println("Rooms still tracked by RoomManager: " + manager.roomCount());
        } finally {
            manager.close();
            answerScheduler.shutdownNow();
        }
        System.out.println();
        System.out.println("=== simulation complete ===");
    }

    private static List<SimulationSession> createRoom(RoomManager manager,
                                                      ScheduledExecutorService answerScheduler,
                                                      String roomName,
                                                      String... usernames) {
        List<SimulationSession> sessions = new ArrayList<>();
        for (int i = 0; i < usernames.length; i++) {
            sessions.add(new SimulationSession(
                    "sim-" + usernames[i], usernames[i], i, manager, answerScheduler));
        }
        for (SimulationSession session : sessions) {
            manager.onConnect(session);
        }

        SimulationSession host = sessions.get(0);
        manager.onMessage(host, new Message(MessageType.CREATE_ROOM,
                List.of(roomName, String.valueOf(QUESTIONS_PER_ROOM))));
        Message joined = host.last(MessageType.JOINED);
        if (joined == null) {
            throw new IllegalStateException("Host never received JOINED for room " + roomName);
        }
        String roomId = joined.getField(0);
        for (int i = 1; i < sessions.size(); i++) {
            manager.onMessage(sessions.get(i), new Message(MessageType.JOIN, List.of(roomId)));
        }
        System.out.println("Room " + roomId + " (" + roomName + ") has " + sessions.size() + " players");
        return sessions;
    }

    private static void printHandlerThreads(RoomManager manager) throws Exception {
        List<String> names = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            manager.executor().execute(() -> {
                names.add(Thread.currentThread().getName());
                done.countDown();
            });
        }
        done.await(2, TimeUnit.SECONDS);
        Collections.sort(names);
        System.out.println("Handler pool threads: " + names);
    }

    private static void printLeaderboards(PrintingPublisher publisher) {
        for (Map.Entry<String, Map<String, Integer>> entry : publisher.lastLeaderboards.entrySet()) {
            System.out.println("room " + entry.getKey() + " -> " + entry.getValue());
        }
    }

    private static AppConfig simulationConfig() throws IOException {
        Path file = Files.createTempFile("m4-simulation", ".properties");
        try {
            Files.writeString(file, String.join("\n",
                    "game.handler.pool.size=4",
                    "game.question.time.seconds=2",
                    "game.questions.file=questions/sample-questions.csv",
                    "game.questions.url=",
                    "game.round.gap.millis=400",
                    "game.leaderboard.throttle.millis=100",
                    ""));
            return AppConfig.load(file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * A fake client. It prints everything it receives (with the delivering thread name)
     * and answers each question after a short, player-specific delay.
     */
    private static final class SimulationSession implements ClientSession {

        private final String id;
        private final String username;
        private final int playerIndex;
        private final RoomManager manager;
        private final ScheduledExecutorService answerScheduler;
        private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger questionCounter = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch gameOver = new CountDownLatch(1);
        private final SocketAddress remoteAddress;

        SimulationSession(String id, String username, int playerIndex,
                          RoomManager manager, ScheduledExecutorService answerScheduler) {
            this.id = id;
            this.username = username;
            this.playerIndex = playerIndex;
            this.manager = manager;
            this.answerScheduler = answerScheduler;
            this.remoteAddress = new InetSocketAddress("127.0.0.1", 10_000 + playerIndex);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<String> username() {
            return Optional.of(username);
        }

        @Override
        public SocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public void send(Message message) {
            if (closed.get()) {
                return;
            }
            messages.add(message);
            System.out.println("[" + Thread.currentThread().getName() + "] "
                    + username + " <- " + describe(message));

            switch (message.type()) {
                case QUESTION -> scheduleAnswer(message);
                case GAME_OVER -> gameOver.countDown();
                default -> {
                    // RESULT, JOINED, PLAYER_JOINED, ERROR - already printed.
                }
            }
        }

        private void scheduleAnswer(Message question) {
            int questionNumber = questionCounter.getAndIncrement();
            String questionId = question.getField(0);
            int choice = Math.floorMod(playerIndex + questionNumber, Question.OPTION_COUNT);
            long delayMillis = 150L + playerIndex * 90L + questionNumber * 40L;
            answerScheduler.schedule(() -> {
                if (closed.get()) {
                    return;
                }
                System.out.println("[" + Thread.currentThread().getName() + "] "
                        + username + " -> ANSWER " + questionId + " choice=" + choice);
                manager.onMessage(this, new Message(MessageType.ANSWER,
                        List.of(questionId, String.valueOf(choice))));
            }, delayMillis, TimeUnit.MILLISECONDS);
        }

        private static String describe(Message message) {
            StringBuilder builder = new StringBuilder(message.type().name());
            int limit = message.type() == MessageType.QUESTION ? 4 : message.fieldCount();
            for (int i = 0; i < Math.min(limit, message.fieldCount()); i++) {
                builder.append(" | ").append(message.getField(i));
            }
            return builder.toString();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        Message last(MessageType type) {
            synchronized (messages) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i).type() == type) {
                        return messages.get(i);
                    }
                }
            }
            return null;
        }

        boolean awaitGameOver(long timeout, TimeUnit unit) throws InterruptedException {
            return gameOver.await(timeout, unit);
        }
    }

    /** Prints live-feed events and remembers the last leaderboard per room. */
    private static final class PrintingPublisher implements LiveFeedPublisher {

        private final Map<String, Map<String, Integer>> lastLeaderboards = new ConcurrentHashMap<>();

        @Override
        public void publishCountdown(String roomId, int secondsLeft) {
            System.out.println("[" + Thread.currentThread().getName() + "] "
                    + "feed room=" + roomId + " countdown=" + secondsLeft + "s");
        }

        @Override
        public void publishLeaderboard(String roomId, Map<String, Integer> scores) {
            Map<String, Integer> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
            lastLeaderboards.put(roomId, snapshot);
            System.out.println("[" + Thread.currentThread().getName() + "] "
                    + "feed room=" + roomId + " leaderboard=" + snapshot);
        }

        @Override
        public void close() {
            // Nothing to close.
        }
    }
}
