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

class DisconnectTest {

    private RecordingLiveFeedPublisher feed;
    private RoomManager manager;
    private TestConfig.MutableClock clock;

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
    void disconnectInLobbyRemovesThePlayerAndNotifiesTheRoom() {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", "1"));
        String roomId = host.last(MessageType.JOINED).getField(0);
        RecordingClientSession bob = connect("bob");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));

        manager.onDisconnect(bob);

        Message left = host.last(MessageType.PLAYER_LEFT);
        assertNotNull(left);
        assertEquals("bob", left.getField(0));
        assertEquals(1, manager.roomCount());
        manager.onMessage(host, msg(MessageType.LIST_ROOMS));
        assertEquals(roomId + ":Room:1:LOBBY", host.last(MessageType.ROOMS).getField(0));
    }

    @Test
    void hostDisconnectPromotesTheEarliestRemainingPlayer() {
        RecordingClientSession alice = connect("alice");
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "1"));
        String roomId = alice.last(MessageType.JOINED).getField(0);
        RecordingClientSession bob = connect("bob");
        RecordingClientSession carol = connect("carol");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        manager.onMessage(carol, msg(MessageType.JOIN, roomId));

        manager.onDisconnect(alice);

        assertEquals(2, bob.count(MessageType.JOINED));
        assertEquals("true", bob.last(MessageType.JOINED).getField(2));
        assertEquals(1, carol.count(MessageType.JOINED));
        assertEquals("alice", bob.last(MessageType.PLAYER_LEFT).getField(0));

        manager.onMessage(bob, msg(MessageType.START));
        assertNotNull(bob.last(MessageType.QUESTION));
        assertNotNull(carol.last(MessageType.QUESTION));
    }

    @Test
    void disconnectDuringARoundDoesNotStallTheGame() {
        createRunningGame("alice", "bob");
        RecordingClientSession alice = connectExisting("alice");
        RecordingClientSession bob = connectExisting("bob");
        String roomId = alice.last(MessageType.JOINED).getField(0);
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);
        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));

        int bobMessagesAtDisconnect = bob.messageCount();
        manager.onDisconnect(bob);

        assertTrue(alice.awaitMessage(MessageType.PLAYER_LEFT, 1, 1_000));
        assertTrue(alice.awaitMessage(MessageType.RESULT, 1, 3_000),
                "the remaining player must still receive RESULT");
        assertTrue(alice.awaitGameOver(3_000), "the game must still finish");

        assertEquals(bobMessagesAtDisconnect, bob.messageCount(),
                "a disconnected player must not receive more messages");
        assertEquals(Map.of("alice", 150), TestConfig.standings(alice.last(MessageType.GAME_OVER)));
        assertEquals(1, manager.roomCount());
    }

    @Test
    void hostDisconnectDuringARoundKeepsTheGameRunning() {
        createRunningGame("alice", "bob", "carol");
        RecordingClientSession alice = connectExisting("alice");
        RecordingClientSession bob = connectExisting("bob");
        RecordingClientSession carol = connectExisting("carol");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onDisconnect(alice);

        assertEquals("true", bob.last(MessageType.JOINED).getField(2));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(carol, msg(MessageType.ANSWER, questionId, "1"));

        assertTrue(bob.awaitGameOver(3_000));
        assertTrue(carol.awaitGameOver(3_000));
        assertEquals("bob:150;carol:150", bob.last(MessageType.GAME_OVER).getField(1));
    }

    @Test
    void lastPlayerDisconnectRemovesTheRoomAndStopsTimers() throws IOException {
        manager.close();
        manager = new RoomManager(
                TestConfig.create("game.question.time.seconds", "10"),
                feed, TestConfig.source(1), clock);
        RecordingClientSession alice = connect("alice");
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Solo", "1"));
        String roomId = alice.last(MessageType.JOINED).getField(0);
        manager.onMessage(alice, msg(MessageType.START));

        assertTrue(feed.awaitCountdown(roomId, 1, 2_000));
        int countdownsBefore = feed.countdownsFor(roomId).size();

        manager.onDisconnect(alice);

        assertEquals(0, manager.roomCount());
        assertFalse(feed.awaitCountdown(roomId, countdownsBefore + 1, 500),
                "cancelled room timers must not publish more countdowns");
    }

    @Test
    void disconnectBeforeAnsweringExcludesThePlayerFromStandings() {
        createRunningGame("alice", "bob");
        RecordingClientSession alice = connectExisting("alice");
        RecordingClientSession bob = connectExisting("bob");
        manager.onMessage(alice, msg(MessageType.START));
        String questionId = alice.last(MessageType.QUESTION).getField(0);

        manager.onDisconnect(bob);
        manager.onMessage(alice, msg(MessageType.ANSWER, questionId, "1"));

        assertTrue(alice.awaitGameOver(3_000));
        Map<String, Integer> standings = TestConfig.standings(alice.last(MessageType.GAME_OVER));
        assertEquals(1, standings.size());
        assertTrue(standings.containsKey("alice"));
        assertFalse(standings.containsKey("bob"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private RecordingClientSession alice;
    private RecordingClientSession bob;
    private RecordingClientSession carol;

    private void createRunningGame(String... usernames) {
        alice = connect(usernames[0]);
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "1"));
        String roomId = alice.last(MessageType.JOINED).getField(0);
        if (usernames.length > 1) {
            bob = connect(usernames[1]);
            manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        }
        if (usernames.length > 2) {
            carol = connect(usernames[2]);
            manager.onMessage(carol, msg(MessageType.JOIN, roomId));
        }
    }

    private RecordingClientSession connectExisting(String username) {
        return switch (username) {
            case "alice" -> alice;
            case "bob" -> bob;
            case "carol" -> carol;
            default -> throw new IllegalArgumentException(username);
        };
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
