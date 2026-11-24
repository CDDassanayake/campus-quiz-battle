package com.quizbattle.game;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoRoomIsolationTest {

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
    void twoRoomsPlaySimultaneouslyWithoutInterference() {
        RecordingClientSession alice = connect("alice");
        RecordingClientSession bob = connect("bob");
        RecordingClientSession carol = connect("carol");
        RecordingClientSession dave = connect("dave");

        String roomA = createRoom(alice, "Alpha");
        String roomB = createRoom(carol, "Beta");
        manager.onMessage(bob, msg(MessageType.JOIN, roomA));
        manager.onMessage(dave, msg(MessageType.JOIN, roomB));

        manager.onMessage(alice, msg(MessageType.START));
        manager.onMessage(carol, msg(MessageType.START));

        String questionA = alice.last(MessageType.QUESTION).getField(0);
        String questionB = carol.last(MessageType.QUESTION).getField(0);
        assertNotNull(questionA);
        assertNotNull(questionB);

        manager.onMessage(alice, msg(MessageType.ANSWER, questionA, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionA, "1"));
        manager.onMessage(carol, msg(MessageType.ANSWER, questionB, "1"));
        manager.onMessage(dave, msg(MessageType.ANSWER, questionB, "0"));

        assertTrue(alice.awaitGameOver(3_000));
        assertTrue(bob.awaitGameOver(3_000));
        assertTrue(carol.awaitGameOver(3_000));
        assertTrue(dave.awaitGameOver(3_000));

        assertEquals("alice:150;bob:150", alice.last(MessageType.GAME_OVER).getField(1));
        assertEquals("carol:150;dave:0", carol.last(MessageType.GAME_OVER).getField(1));
        assertEquals("alice", alice.last(MessageType.GAME_OVER).getField(0));
        assertEquals("carol", carol.last(MessageType.GAME_OVER).getField(0));

        assertNoCrossTalk(alice, roomA, Set.of("alice", "bob"));
        assertNoCrossTalk(bob, roomA, Set.of("alice", "bob"));
        assertNoCrossTalk(carol, roomB, Set.of("carol", "dave"));
        assertNoCrossTalk(dave, roomB, Set.of("carol", "dave"));

        assertTrue(feed.awaitLeaderboardScores(roomA,
                scores -> Map.of("alice", 150, "bob", 150).equals(scores), 2_000),
                "room A's final leaderboard");
        assertTrue(feed.awaitLeaderboardScores(roomB,
                scores -> Map.of("carol", 150, "dave", 0).equals(scores), 2_000),
                "room B's final leaderboard");

        RecordingLiveFeedPublisher.LeaderboardEvent leaderboardA = feed.lastLeaderboard(roomA);
        RecordingLiveFeedPublisher.LeaderboardEvent leaderboardB = feed.lastLeaderboard(roomB);
        assertNotNull(leaderboardA);
        assertNotNull(leaderboardB);
        assertEquals(Map.of("alice", 150, "bob", 150), leaderboardA.scores());
        assertEquals(Map.of("carol", 150, "dave", 0), leaderboardB.scores());
        assertEquals(2, manager.roomCount());
    }

    @Test
    void finishingOneRoomDoesNotFinishTheOther() throws IOException {
        manager.close();
        manager = new RoomManager(
                TestConfig.create("game.question.time.seconds", "30"),
                feed, TestConfig.source(3), clock);

        RecordingClientSession alice = connect("alice");
        RecordingClientSession bob = connect("bob");
        String roomA = createRoom(alice, "Alpha");
        manager.onMessage(bob, msg(MessageType.JOIN, roomA));
        manager.onMessage(alice, msg(MessageType.START));
        String questionA = alice.last(MessageType.QUESTION).getField(0);
        manager.onMessage(alice, msg(MessageType.ANSWER, questionA, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionA, "1"));
        assertTrue(alice.awaitGameOver(3_000));

        RecordingClientSession carol = connect("carol");
        manager.onMessage(carol, msg(MessageType.CREATE_ROOM, "Beta", "1"));
        String roomB = carol.last(MessageType.JOINED).getField(0);
        manager.onMessage(carol, msg(MessageType.START));

        manager.onMessage(carol, msg(MessageType.LIST_ROOMS));
        String listedRooms = carol.last(MessageType.ROOMS).getField(0);
        assertTrue(listedRooms.contains(roomA + ":Alpha:2:FINISHED"),
                "room A must stay finished and independent: " + listedRooms);
        assertTrue(listedRooms.contains(roomB + ":Beta:1:RUNNING"),
                "room B must be running independently: " + listedRooms);
        assertNotNull(carol.last(MessageType.QUESTION));
        assertEquals(0, carol.count(MessageType.GAME_OVER));
    }

    private void assertNoCrossTalk(RecordingClientSession session, String roomId, Set<String> allowedNames) {
        for (Message joined : session.messages(MessageType.JOINED)) {
            assertEquals(roomId, joined.getField(0));
        }
        for (Message playerJoined : session.messages(MessageType.PLAYER_JOINED)) {
            assertTrue(allowedNames.contains(playerJoined.getField(0)));
        }
        Message gameOver = session.last(MessageType.GAME_OVER);
        assertNotNull(gameOver);
        for (String name : TestConfig.standings(gameOver).keySet()) {
            assertTrue(allowedNames.contains(name), "leaked player " + name + " into " + roomId);
        }
    }

    private String createRoom(RecordingClientSession host, String name) {
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, name, "1"));
        return host.last(MessageType.JOINED).getField(0);
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
