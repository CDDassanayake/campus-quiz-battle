package com.quizbattle.game;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress test: 50 players split over two rooms answer at the same time,
 * each player also sends a duplicate answer to prove that scoring is exactly-once.
 *
 * <p>The scoring clock is frozen, so every correct answer is worth exactly 150 points
 * and any double-award would be visible immediately. The test runs 100 iterations by
 * default; override with {@code -Dgame.stress.iterations=N}.</p>
 */
class ConcurrencyTest {

    private static final int PLAYERS_PER_ROOM = 25;
    private static final int TOTAL_PLAYERS = 50;
    private static final int EXPECTED_POINTS = 150;

    @Test
    void fiftySessionsInTwoRoomsStressTest() throws Exception {
        int iterations = Integer.getInteger("game.stress.iterations", 100);
        long startedAt = System.nanoTime();
        for (int iteration = 1; iteration <= iterations; iteration++) {
            runIteration(iteration);
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        System.out.println("Concurrency stress test passed: "
                + iterations + " iterations x " + TOTAL_PLAYERS + " sessions in " + elapsedMillis + " ms");
    }

    private void runIteration(int iteration) throws Exception {
        RecordingLiveFeedPublisher feed = new RecordingLiveFeedPublisher();
        TestConfig.MutableClock clock = new TestConfig.MutableClock(1_000_000_000L);
        RoomManager manager = new RoomManager(TestConfig.create(
                "game.question.time.seconds", "5",
                "game.round.gap.millis", "1",
                "game.room.max.players", "30",
                "game.handler.pool.size", "8",
                "game.leaderboard.throttle.millis", "0"),
                feed, TestConfig.source(1), clock);

        ExecutorService answerPool = Executors.newFixedThreadPool(16, daemonFactory("m4-stress-answer"));
        try {
            List<RecordingClientSession> sessions = new ArrayList<>(TOTAL_PLAYERS);
            for (int i = 1; i <= TOTAL_PLAYERS; i++) {
                RecordingClientSession session = new RecordingClientSession(
                        String.format("session-%02d", i), String.format("player%02d", i));
                manager.onConnect(session);
                sessions.add(session);
            }
            List<RecordingClientSession> roomA = sessions.subList(0, PLAYERS_PER_ROOM);
            List<RecordingClientSession> roomB = sessions.subList(PLAYERS_PER_ROOM, TOTAL_PLAYERS);

            String roomAId = createRoom(manager, roomA, "Alpha", iteration);
            String roomBId = createRoom(manager, roomB, "Beta", iteration);

            manager.onMessage(roomA.get(0), msg(MessageType.START));
            manager.onMessage(roomB.get(0), msg(MessageType.START));

            String questionA = roomA.get(0).last(MessageType.QUESTION).getField(0);
            String questionB = roomB.get(0).last(MessageType.QUESTION).getField(0);
            for (RecordingClientSession session : sessions) {
                assertTrue(session.awaitMessage(MessageType.QUESTION, 1, 2_000),
                        "iteration " + iteration + ": " + session.usernameValue() + " missed QUESTION");
            }

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch answered = new CountDownLatch(TOTAL_PLAYERS);
            for (int i = 0; i < TOTAL_PLAYERS; i++) {
                RecordingClientSession session = sessions.get(i);
                String questionId = i < PLAYERS_PER_ROOM ? questionA : questionB;
                answerPool.execute(() -> {
                    try {
                        startGate.await();
                        // First answer is accepted; the immediate duplicate must be
                        // rejected with ALREADY_ANSWERED or TOO_LATE (if the round has
                        // already closed).
                        manager.onMessage(session, msg(MessageType.ANSWER, questionId, "1"));
                        manager.onMessage(session, msg(MessageType.ANSWER, questionId, "1"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        answered.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(answered.await(15, TimeUnit.SECONDS),
                    "iteration " + iteration + ": answers did not complete in time");

            for (RecordingClientSession session : sessions) {
                assertTrue(session.awaitGameOver(10_000),
                        "iteration " + iteration + ": " + session.usernameValue() + " missed GAME_OVER");
            }

            verifyRoom(roomA, roomAId, iteration, feed);
            verifyRoom(roomB, roomBId, iteration, feed);
            assertTrue(feed.countdownsFor(roomAId).size() >= 1);
            assertTrue(feed.countdownsFor(roomBId).size() >= 1);
        } finally {
            manager.close();
            answerPool.shutdownNow();
        }
        assertTrue(manager.executor().isShutdown());
    }

    private void verifyRoom(List<RecordingClientSession> room, String roomId, int iteration,
                            RecordingLiveFeedPublisher feed) {
        Set<String> expectedNames = new HashSet<>();
        for (RecordingClientSession session : room) {
            expectedNames.add(session.usernameValue());
        }
        String expectedWinner = room.get(0).usernameValue();

        for (RecordingClientSession session : room) {
            String who = session.usernameValue();
            String context = "iteration " + iteration + ", " + who + ": ";

            assertEquals(1, session.count(MessageType.QUESTION), context + "QUESTION count");
            assertEquals(1, session.count(MessageType.RESULT), context + "exactly one RESULT per player");
            assertEquals(1, session.count(MessageType.GAME_OVER), context + "GAME_OVER count");
            assertEquals(1, session.errorCount("ALREADY_ANSWERED", "TOO_LATE"),
                    context + "the duplicate answer must be rejected exactly once");
            assertEquals(1, session.count(MessageType.ERROR),
                    context + "no unexpected ERROR messages: " + session.messages(MessageType.ERROR));

            Message result = session.last(MessageType.RESULT);
            assertEquals(EXPECTED_POINTS, TestConfig.points(result), context + "points awarded once");
            assertEquals(EXPECTED_POINTS, TestConfig.total(result), context + "total score not doubled");

            Message gameOver = session.last(MessageType.GAME_OVER);
            assertEquals(expectedWinner, gameOver.getField(0), context + "winner");
            Map<String, Integer> standings = TestConfig.standings(gameOver);
            assertEquals(expectedNames, standings.keySet(), context + "standings only contain room members");
            assertTrue(standings.values().stream().allMatch(score -> score == EXPECTED_POINTS),
                    context + "every player scored exactly once");
        }

        assertTrue(feed.awaitLeaderboardScores(roomId,
                        scores -> scores.keySet().equals(expectedNames)
                                && scores.values().stream().allMatch(score -> score == EXPECTED_POINTS),
                        2_000),
                "iteration " + iteration + ": final leaderboard for " + roomId + " was not published");
        RecordingLiveFeedPublisher.LeaderboardEvent finalLeaderboard = feed.lastLeaderboard(roomId);
        assertNotNull(finalLeaderboard, "iteration " + iteration + ": leaderboard for " + roomId);
        assertEquals(expectedNames, finalLeaderboard.scores().keySet());
        assertTrue(finalLeaderboard.scores().values().stream().allMatch(score -> score == EXPECTED_POINTS));

        for (RecordingLiveFeedPublisher.LeaderboardEvent event : feed.leaderboardsFor(roomId)) {
            assertEquals(expectedNames, event.scores().keySet(),
                    "iteration " + iteration + ": a leaderboard leaked players from another room");
        }
    }

    private static String createRoom(RoomManager manager, List<RecordingClientSession> room,
                                     String name, int iteration) {
        RecordingClientSession host = room.get(0);
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, name, "1"));
        Message joined = host.last(MessageType.JOINED);
        assertNotNull(joined, "iteration " + iteration + ": host did not create room " + name);
        String roomId = joined.getField(0);
        for (int i = 1; i < room.size(); i++) {
            manager.onMessage(room.get(i), msg(MessageType.JOIN, roomId));
            assertNotNull(room.get(i).last(MessageType.JOINED),
                    "iteration " + iteration + ": " + room.get(i).usernameValue() + " did not join");
        }
        return roomId;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Message msg(MessageType type, String... fields) {
        return new Message(type, List.of(fields));
    }
}
