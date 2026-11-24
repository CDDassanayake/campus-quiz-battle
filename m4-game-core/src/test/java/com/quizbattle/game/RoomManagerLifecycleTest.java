package com.quizbattle.game;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomManagerLifecycleTest {

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
    void rejectsDuplicateUsernames() {
        RecordingClientSession first = connect("alice");
        RecordingClientSession second = connect("alice");

        assertNotNull(second.errorWithCode("ALREADY_CONNECTED"));
        assertTrue(second.isClosed(), "the duplicate connection must be closed");
        assertFalse(first.isClosed(), "the original connection must stay open");

        // The original session still works.
        manager.onMessage(first, msg(MessageType.LIST_ROOMS));
        assertNotNull(first.last(MessageType.ROOMS));
    }

    @Test
    void rejectedDuplicateSessionCannotEvictTheOriginalPlayer() {
        RecordingClientSession alice = connect("alice");
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "1"));
        String roomId = alice.last(MessageType.JOINED).getField(0);

        RecordingClientSession duplicate = connect("alice");
        assertNotNull(duplicate.errorWithCode("ALREADY_CONNECTED"));

        // The gateway always calls onDisconnect for the session it opened, including a
        // session that was rejected as a duplicate. That must not remove the original
        // player from the room or notify anyone that they left.
        manager.onDisconnect(duplicate);

        assertFalse(alice.isClosed(), "the original connection must stay open");
        assertEquals(0, alice.count(MessageType.PLAYER_LEFT),
                "a rejected duplicate must not broadcast PLAYER_LEFT for the original player");
        assertEquals(1, manager.roomCount(), "the original player's room must still exist");

        manager.onMessage(alice, msg(MessageType.LIST_ROOMS));
        assertEquals(roomId + ":Room:1:LOBBY", alice.last(MessageType.ROOMS).getField(0));

        // The original session must still be able to play normally.
        manager.onMessage(alice, msg(MessageType.START));
        assertNotNull(alice.last(MessageType.QUESTION));

        // Reconnecting after the original really disconnects still works.
        manager.onDisconnect(alice);
        assertEquals(0, manager.roomCount(), "the room is removed when its last player leaves");
        RecordingClientSession reconnected = connect("alice");
        assertFalse(reconnected.isClosed());
        assertNull(reconnected.errorWithCode("ALREADY_CONNECTED"));
    }

    @Test
    void createsListsAndJoinsRooms() {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Networks Lab", "2"));

        Message joined = host.last(MessageType.JOINED);
        assertNotNull(joined);
        String roomId = joined.getField(0);
        assertEquals(roomId, joined.getField(0));
        assertEquals("Networks Lab", joined.getField(1));
        assertEquals("true", joined.getField(2));

        manager.onMessage(host, msg(MessageType.LIST_ROOMS));
        assertEquals(roomId + ":Networks Lab:1:LOBBY", host.last(MessageType.ROOMS).getField(0));

        RecordingClientSession bob = connect("bob");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));

        Message bobJoined = bob.last(MessageType.JOINED);
        assertNotNull(bobJoined);
        assertEquals(roomId, bobJoined.getField(0));
        assertEquals("false", bobJoined.getField(2));

        Message playerJoined = host.last(MessageType.PLAYER_JOINED);
        assertNotNull(playerJoined);
        assertEquals("bob", playerJoined.getField(0));

        manager.onMessage(host, msg(MessageType.LIST_ROOMS));
        assertEquals(roomId + ":Networks Lab:2:LOBBY", host.last(MessageType.ROOMS).getField(0));
    }

    @Test
    void joinUnknownRoomReturnsRoomNotFound() {
        RecordingClientSession bob = connect("bob");

        manager.onMessage(bob, msg(MessageType.JOIN, "r999"));

        assertNotNull(bob.errorWithCode("ROOM_NOT_FOUND"));
    }

    @Test
    void joiningTheSameRoomTwiceIsIdempotent() {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", "1"));
        RecordingClientSession bob = connect("bob");
        String roomId = host.last(MessageType.JOINED).getField(0);

        manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));

        assertEquals(2, bob.count(MessageType.JOINED));
        assertNull(bob.errorWithCode("BAD_MESSAGE"));
    }

    @Test
    void leavingNotifiesRemainingPlayers() {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", "1"));
        RecordingClientSession bob = connect("bob");
        manager.onMessage(bob, msg(MessageType.JOIN, host.last(MessageType.JOINED).getField(0)));

        manager.onMessage(bob, msg(MessageType.LEAVE));

        Message left = host.last(MessageType.PLAYER_LEFT);
        assertNotNull(left);
        assertEquals("bob", left.getField(0));
        manager.onMessage(host, msg(MessageType.LIST_ROOMS));
        assertTrue(host.last(MessageType.ROOMS).getField(0).endsWith(":1:LOBBY"));
    }

    @Test
    void hostDeparturePromotesTheEarliestRemainingPlayer() {
        RecordingClientSession alice = connect("alice");
        manager.onMessage(alice, msg(MessageType.CREATE_ROOM, "Room", "1"));
        String roomId = alice.last(MessageType.JOINED).getField(0);
        RecordingClientSession bob = connect("bob");
        RecordingClientSession carol = connect("carol");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        manager.onMessage(carol, msg(MessageType.JOIN, roomId));

        manager.onMessage(alice, msg(MessageType.LEAVE));

        Message promotion = bob.last(MessageType.JOINED);
        assertEquals(2, bob.count(MessageType.JOINED), "bob must receive a second JOINED");
        assertEquals("true", promotion.getField(2));
        assertEquals(1, carol.count(MessageType.JOINED), "carol must not be promoted");

        // The promoted player can now start the game.
        manager.onMessage(bob, msg(MessageType.START));
        assertNotNull(bob.last(MessageType.QUESTION));
        assertNotNull(carol.last(MessageType.QUESTION));
    }

    @Test
    void enforcesRoomCapacity() throws IOException {
        manager.close();
        manager = new RoomManager(
                TestConfig.create("game.room.max.players", "2"),
                feed, TestConfig.source(2), clock);

        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Small", "1"));
        String roomId = host.last(MessageType.JOINED).getField(0);
        RecordingClientSession bob = connect("bob");
        RecordingClientSession carol = connect("carol");

        manager.onMessage(bob, msg(MessageType.JOIN, roomId));
        manager.onMessage(carol, msg(MessageType.JOIN, roomId));

        assertNull(bob.errorWithCode("ROOM_FULL"));
        assertNotNull(carol.errorWithCode("ROOM_FULL"));
    }

    @Test
    void clampsQuestionCountToTheBankSize() {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Big", "99"));
        manager.onMessage(host, msg(MessageType.START));

        Message question = host.last(MessageType.QUESTION);
        assertNotNull(question);
        assertEquals("3", question.getField(2), "three questions are available");
    }

    @Test
    void finishedRoomIsRemovedWhenTheLastPlayerLeaves() {
        RecordingClientSession observer = connect("observer");
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Tiny", "1"));
        String roomId = host.last(MessageType.JOINED).getField(0);
        RecordingClientSession bob = connect("bob");
        manager.onMessage(bob, msg(MessageType.JOIN, roomId));

        manager.onMessage(host, msg(MessageType.START));
        assertTrue(host.awaitMessage(MessageType.QUESTION, 1, 1_000));
        String questionId = host.last(MessageType.QUESTION).getField(0);
        manager.onMessage(host, msg(MessageType.ANSWER, questionId, "1"));
        manager.onMessage(bob, msg(MessageType.ANSWER, questionId, "1"));
        assertTrue(host.awaitGameOver(3_000));

        manager.onMessage(host, msg(MessageType.LEAVE));
        manager.onMessage(bob, msg(MessageType.LEAVE));

        assertEquals(0, manager.roomCount());
        manager.onMessage(observer, msg(MessageType.LIST_ROOMS));
        assertEquals("", observer.last(MessageType.ROOMS).getField(0));
    }

    @Test
    void handlerAndSchedulerThreadsUseTheDocumentedNames() throws Exception {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", "1"));
        manager.onMessage(host, msg(MessageType.START));

        String roomId = host.last(MessageType.JOINED).getField(0);
        assertTrue(feed.awaitCountdown(roomId, 1, 2_000), "the scheduler must publish a countdown");

        List<String> executorNames = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            manager.executor().submit(() -> executorNames.add(Thread.currentThread().getName())).get(2, TimeUnit.SECONDS);
        }

        assertEquals(4, executorNames.size());
        for (String name : executorNames) {
            assertTrue(name.startsWith("m4-handler-"), "unexpected handler thread: " + name);
        }
        for (RecordingLiveFeedPublisher.CountdownEvent event : feed.countdownsFor(roomId)) {
            assertTrue(event.threadName().startsWith("m4-scheduler-"),
                    "unexpected scheduler thread: " + event.threadName());
        }
    }

    @Test
    void closeIsIdempotentAndTerminatesBothPools() throws Exception {
        RecordingClientSession host = connect("alice");
        manager.onMessage(host, msg(MessageType.CREATE_ROOM, "Room", "1"));

        manager.close();
        manager.close();

        assertTrue(manager.executor().isShutdown());
        assertTrue(manager.executor().awaitTermination(2, TimeUnit.SECONDS));
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
