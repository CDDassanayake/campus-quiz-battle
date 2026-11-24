package com.quizbattle.game;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomManagerErrorsTest {

    private RecordingLiveFeedPublisher feed;
    private RoomManager manager;
    private TestConfig.MutableClock clock;

    private RecordingClientSession host;
    private RecordingClientSession bob;
    private String roomId;
    private String questionId;

    @BeforeEach
    void setUp() throws IOException {
        feed = new RecordingLiveFeedPublisher();
        clock = new TestConfig.MutableClock(1_000_000_000L);
        // Long rounds so timers never interfere with error assertions.
        manager = new RoomManager(
                TestConfig.create("game.question.time.seconds", "30"),
                feed, TestConfig.source(3), clock);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void onlyTheHostCanStartTheGame() {
        createLobby(2);

        manager.onMessage(bob, msg(MessageType.START));

        assertNotNull(bob.errorWithCode("NOT_HOST"));
    }

    @Test
    void cannotStartTwice() {
        startGame();

        manager.onMessage(host, msg(MessageType.START));

        assertNotNull(host.errorWithCode("GAME_RUNNING"));
    }

    @Test
    void cannotJoinARunningRoom() {
        startGame();
        RecordingClientSession carol = connect("carol");

        manager.onMessage(carol, msg(MessageType.JOIN, roomId));

        assertNotNull(carol.errorWithCode("GAME_RUNNING"));
    }

    @Test
    void cannotAnswerWhenNotInARoom() {
        RecordingClientSession loner = connect("loner");

        manager.onMessage(loner, msg(MessageType.ANSWER, "1", "1"));

        assertNotNull(loner.errorWithCode("NOT_IN_ROOM"));
    }

    @Test
    void cannotLeaveWhenNotInARoom() {
        RecordingClientSession loner = connect("loner");

        manager.onMessage(loner, msg(MessageType.LEAVE));

        assertNotNull(loner.errorWithCode("NOT_IN_ROOM"));
    }

    @Test
    void cannotAnswerBeforeTheGameStarts() {
        createLobby(2);

        manager.onMessage(host, msg(MessageType.ANSWER, "1", "1"));

        assertNotNull(host.errorWithCode("BAD_MESSAGE"));
    }

    @Test
    void rejectsInvalidChoiceIndexes() {
        startGame();

        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "4"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "-1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "not-a-number"));

        assertEquals(3, bob.errorCount("BAD_MESSAGE"));
    }

    @Test
    void rejectsAnAnswerForTheWrongQuestion() {
        startGame();

        manager.onMessage(bob, msg(MessageType.ANSWER, "999", "1"));

        assertNotNull(bob.errorWithCode("BAD_MESSAGE"));
    }

    @Test
    void onlyTheFirstAnswerCounts() {
        startGame();

        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));

        assertNotNull(bob.errorWithCode("ALREADY_ANSWERED"));
    }

    @Test
    void rejectsAnswersAfterTheDeadline() {
        startGame(); // 30 s limit
        clock.advanceMillis(31_000);

        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));

        assertNotNull(bob.errorWithCode("TOO_LATE"));
    }

    @Test
    void rejectsAnswersAfterTheGameFinished() {
        createLobby(1);
        manager.onMessage(host, msg(MessageType.START));
        questionId = host.last(MessageType.QUESTION).getField(0);
        manager.onMessage(host, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        assertTrue(host.awaitGameOver(3_000));

        manager.onMessage(host, msg(MessageType.ANSWER, questionId, "1"));

        assertNotNull(host.errorWithCode("TOO_LATE"));
    }

    @Test
    void rejectsInvalidCreateRoomMessages() {
        RecordingClientSession alice = connect("alice");

        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "bad:name", "1"));
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "x".repeat(25), "1"));
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "abc"));
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "0"));

        assertEquals(4, alice.errorCount("BAD_MESSAGE"));
    }

    @Test
    void rejectsCreatingASecondRoom() {
        createLobby(2);

        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Another", "1"));

        assertNotNull(host.errorWithCode("BAD_MESSAGE"));
    }

    @Test
    void rejectsMessagesTheGatewayShouldHandle() {
        RecordingClientSession alice = connect("alice");

        manager.onMessage(alice, new Message(MessageType.WELCOME, List.of("alice")));

        assertNotNull(alice.errorWithCode("BAD_MESSAGE"));
    }

    @Test
    void cannotJoinAFinishedRoom() {
        createLobby(1);
        manager.onMessage(host, msg(MessageType.START));
        String startedQuestion = host.last(MessageType.QUESTION).getField(0);
        manager.onMessage(host, msg(MessageType.ANSWER, startedQuestion, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, startedQuestion, "1"));
        assertTrue(host.awaitGameOver(3_000));

        RecordingClientSession carol = connect("carol");
        manager.onMessage(carol, msg(MessageType.JOIN, roomId));

        assertNotNull(carol.errorWithCode("GAME_RUNNING"));
    }

    @Test
    void rejectsRoomCreationWhenNoQuestionsAreAvailable() throws IOException {
        manager.close();
        manager = new RoomManager(
                TestConfig.create("game.question.time.seconds", "30"),
                feed, TestConfig.source(List.of()), clock);
        RecordingClientSession alice = connect("alice");

        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "1"));

        assertNotNull(alice.errorWithCode("BAD_MESSAGE"));
    }

    private void createLobby(int questionCount) {
        host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", String.valueOf(questionCount)));
        roomId = host.last(MessageType.JOINED).getField(0);
        bob = connect("bob");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));
    }

    private void startGame() {
        createLobby(1);
        manager.onMessage(host, msg(MessageType.START));
        questionId = host.last(MessageType.QUESTION).getField(0);
    }

    private RecordingClientSession connect(String username) {
        RecordingClientSession session = new RecordingClientSession(username);
        manager.onConnect(session);
        return session;
    }

    private static Message msg(MessageType type, String... fields) {
        return new Message(type, List.of(fields));
    }
}
