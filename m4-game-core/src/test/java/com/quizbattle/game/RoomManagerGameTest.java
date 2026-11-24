package com.quizbattle.game;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomManagerGameTest {

    private RecordingLiveFeedPublisher feed;
    private RoomManager manager;
    private TestConfig.MutableClock clock;

    private RecordingClientSession alice;
    private RecordingClientSession bob;
    private RecordingClientSession carol;

    @BeforeEach
    void setUp() throws IOException {
        feed = new RecordingLiveFeedPublisher();
        clock = new TestConfig.MutableClock(1_000_000_000L);
        manager = new RoomManager(TestConfig.create(), feed, TestConfig.source(3), clock);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void broadcastsTheQuestionInProtocolFormat() {
        createGame("Networks", "3", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));

        Message question = alice.last(MessageType.QUESTION);
        assertNotNull(question);
        assertEquals(9, question.fieldCount(),
                "QUESTION|questionId|index|total|text|optA|optB|optC|optD|timeLimitSec");
        assertEquals("1", question.getField(1));
        assertEquals("3", question.getField(2));
        assertEquals("Question " + question.getField(0) + "?", question.getField(3));
        assertEquals("Red", question.getField(4));
        assertEquals("Green", question.getField(5));
        assertEquals("Blue", question.getField(6));
        assertEquals("Yellow", question.getField(7));
        assertEquals("5", question.getField(8));

        assertNotNull(bob.last(MessageType.QUESTION), "every player must receive the question");
    }

    @Test
    void awardsExactPointsAndSendsResultsToEveryone() {
        createGame("Networks", "1", "alice", "bob", "carol");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        clock.advanceMillis(2_500); // 2.5 s left of 5 s -> 100 + 25
        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "0")); // wrong
        clock.advanceMillis(2_000); // 0.5 s left -> 100 + 5
        manager.onMessage(carol, msg(MessageType.ANSWER, questionId, "1"));

        assertTrue(alice.awaitMessage(MessageType.RESULT, 1, 3_000));
        assertTrue(bob.awaitMessage(MessageType.RESULT, 1, 3_000));
        assertTrue(carol.awaitMessage(MessageType.RESULT, 1, 3_000));

        Message aliceResult = alice.last(MessageType.RESULT);
        assertEquals(questionId, aliceResult.getField(0));
        assertEquals("1", aliceResult.getField(1));
        assertEquals(125, TestConfig.points(aliceResult));
        assertEquals(125, TestConfig.total(aliceResult));

        Message bobResult = bob.last(MessageType.RESULT);
        assertEquals(0, TestConfig.points(bobResult), "a wrong answer scores 0");
        assertEquals(0, TestConfig.total(bobResult));

        Message carolResult = carol.last(MessageType.RESULT);
        assertEquals(105, TestConfig.points(carolResult));
        assertEquals(105, TestConfig.total(carolResult));
    }

    @Test
    void nonAnswerersReceiveAZeroResult() throws IOException {
        restartManager("game.question.time.seconds", "1");
        createGame("Room", "1", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));

        assertTrue(bob.awaitMessage(MessageType.RESULT, 1, 3_000));
        Message bobResult = bob.last(MessageType.RESULT);
        assertEquals(0, TestConfig.points(bobResult));
        assertEquals(0, TestConfig.total(bobResult));
        assertEquals("1", bobResult.getField(1), "the correct index is still reported");
    }

    @Test
    void closesTheRoundAutomaticallyWhenTheTimerExpires() throws IOException {
        restartManager("game.question.time.seconds", "1");
        createGame("Room", "1", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));

        assertTrue(alice.awaitMessage(MessageType.RESULT, 1, 3_000));
        assertTrue(bob.awaitMessage(MessageType.RESULT, 1, 3_000));
        assertTrue(alice.awaitGameOver(3_000));
        assertEquals("alice:0;bob:0", alice.last(MessageType.GAME_OVER).getField(1));
        assertEquals("alice", alice.last(MessageType.GAME_OVER).getField(0));
    }

    @Test
    void earlyClosesWhenAllPlayersHaveAnswered() throws IOException {
        restartManager("game.question.time.seconds", "5");
        createGame("Room", "1", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));

        // The timer is 5 s; the game must finish well before it.
        assertTrue(alice.awaitGameOver(2_000), "all players answered, so the round should close early");
    }

    @Test
    void accumulatesScoresAcrossRoundsAndSortsTheFinalStandings() throws IOException {
        restartManager("game.question.time.seconds", "5");
        createGame("Room", "2", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));

        String firstQuestion = alice.last(MessageType.QUESTION).getField(0);
        manager.onMessage(alice, msg(MessageType.ANSWER, firstQuestion, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, firstQuestion, "1"));

        assertTrue(alice.awaitMessage(MessageType.QUESTION, 2, 3_000));
        Message secondQuestion = alice.messages(MessageType.QUESTION).get(1);
        assertEquals("2", secondQuestion.getField(1));
        manager.onMessage(alice, msg(MessageType.ANSWER, secondQuestion.getField(0), "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, secondQuestion.getField(0), "1"));

        assertTrue(alice.awaitGameOver(3_000));
        Message finalResult = alice.last(MessageType.RESULT);
        assertEquals(150, TestConfig.points(finalResult), "frozen clock gives the full speed bonus");
        assertEquals(300, TestConfig.total(finalResult), "two correct answers accumulate");

        Message gameOver = alice.last(MessageType.GAME_OVER);
        assertEquals("alice", gameOver.getField(0));
        assertEquals("alice:300;bob:300", gameOver.getField(1));
        assertEquals(Map.of("alice", 300, "bob", 300), TestConfig.standings(gameOver));
    }

    @Test
    void finalStandingsBreakTiesByUsername() {
        createGame("Room", "1", "alice", "bob", "carol");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(carol, msg(MessageType.ANSWER, questionId, "0"));

        assertTrue(alice.awaitGameOver(3_000));
        Message gameOver = alice.last(MessageType.GAME_OVER);
        assertEquals("alice", gameOver.getField(0));
        assertEquals("alice:150;bob:150;carol:0", gameOver.getField(1));
    }

    @Test
    void publishesCountdownTicksAndLeaderboards() {
        createGame("Room", "1", "alice", "bob");
        String roomId = alice.last(MessageType.JOINED).getField(0);
        manager.onMessage(alice, msg(MessageType.START));

        assertTrue(feed.awaitCountdown(roomId, 1, 2_000));
        RecordingLiveFeedPublisher.CountdownEvent countdown = feed.countdownsFor(roomId).get(0);
        assertEquals(roomId, countdown.roomId());
        assertTrue(countdown.secondsLeft() >= 0 && countdown.secondsLeft() <= 5);
        assertTrue(countdown.threadName().startsWith("m4-scheduler-"));

        String questionId = alice.last(MessageType.QUESTION).getField(0);
        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        assertTrue(alice.awaitGameOver(3_000));

        assertTrue(feed.awaitLeaderboardScores(roomId,
                        scores -> Map.of("alice", 150, "bob", 150).equals(scores), 2_000),
                "the final leaderboard must be published after the round closes");
        RecordingLiveFeedPublisher.LeaderboardEvent leaderboard = feed.lastLeaderboard(roomId);
        assertNotNull(leaderboard);
        assertEquals(Map.of("alice", 150, "bob", 150), leaderboard.scores());
    }

    @Test
    void throttlesLeaderboardPublishingBetweenAnswers() throws IOException {
        // A 60 s window makes the assertion deterministic: only the first accepted answer
        // may publish, while the round close still publishes unconditionally.
        restartManager("game.question.time.seconds", "30",
                "game.leaderboard.throttle.millis", "60000");
        createGame("Room", "1", "alice", "bob", "carol");
        String roomId = alice.last(MessageType.JOINED).getField(0);
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));

        assertEquals(1, feed.leaderboardsFor(roomId).size(),
                "a second answer inside the throttle window must not publish again");

        manager.onMessage(carol, msg(MessageType.ANSWER, questionId, "1"));
        assertTrue(alice.awaitGameOver(3_000));
        // The publisher is called after the room lock is released, so the final
        // leaderboard can arrive just after GAME_OVER on the TCP connection.
        assertTrue(feed.awaitLeaderboard(roomId, 2, 1_000),
                "the round close must always publish, even inside the throttle window");
        assertTrue(feed.awaitLeaderboardScores(roomId,
                        scores -> Map.of("alice", 150, "bob", 150, "carol", 150).equals(scores), 1_000),
                "the final leaderboard must contain every score");
    }

    @Test
    void doesNotSendResultsBeforeTheRoundCloses() {
        createGame("Room", "1", "alice", "bob");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));

        assertEquals(0, alice.count(MessageType.RESULT), "RESULT belongs to the end of the round");
        assertFalse(bob.awaitMessage(MessageType.RESULT, 1, 200), "bob has not answered and the round is open");
    }

    private void createGame(String roomName, String questionCount, String... usernames) {
        alice = connect(usernames[0]);
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, roomName, questionCount));
        String roomId = alice.last(MessageType.JOINED).getField(0);
        bob = usernames.length > 1 ? connect(usernames[1]) : null;
        carol = usernames.length > 2 ? connect(usernames[2]) : null;
        if (bob != null) {
            manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        }
        if (carol != null) {
            manager.onMessage(carol, msg(MessageType.JOIN, roomId));
        }
    }

    private RecordingClientSession connect(String username) {
        RecordingClientSession session = new RecordingClientSession(username);
        manager.onConnect(session);
        return session;
    }

    private void restartManager(String... overrides) throws IOException {
        manager.close();
        manager = new RoomManager(TestConfig.create(overrides), feed, TestConfig.source(3), clock);
    }

    private static Message msg(MessageType type, String... fields) {
        return new Message(type, List.of(fields));
    }
}
