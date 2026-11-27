package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.common.ServerInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class GameClientServiceTest {
    private static final class Rig implements AutoCloseable {
        final FakeGameServer server;
        final ServerInfo info;
        final FakeSecureLoginClient auth = new FakeSecureLoginClient();
        final FakeLiveFeedReceiver feed = new FakeLiveFeedReceiver();
        final FakeServerFinder finder;
        final GameClientService service;
        Rig(FakeGameServer.Script script) throws IOException { this(script, ClientTimeouts.defaults()); }
        Rig(FakeGameServer.Script script, ClientTimeouts timeouts) throws IOException {
            server = new FakeGameServer(script);
            info = new ServerInfo("Mock", "localhost", server.port(), server.port());
            finder = new FakeServerFinder(List.of(info));
            service = new GameClientService(finder, auth, () -> feed, timeouts);
        }
        @Override public void close() throws Exception {
            service.close(); assertTrue(service.awaitTermination(Duration.ofSeconds(3))); server.close();
        }
    }
    private static <T> T done(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static ClientGameState.Snapshot status(Rig rig, GameStatus status) throws Exception {
        return ClientTestSupport.await(rig.service.state(), s -> s.status() == status);
    }
    private static void handshake(java.net.Socket socket, java.io.BufferedReader input) throws Exception {
        assertEquals("HELLO|" + FakeSecureLoginClient.TOKEN, input.readLine()); FakeGameServer.write(socket, "WELCOME|alice\n");
    }

    @Test void discoveryAndRegistrationUseInjectedEndpointsAndClearPasswords() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { })) {
            assertEquals(List.of(rig.info), done(rig.service.discoverServers(Duration.ofMillis(500))));
            assertEquals(Duration.ofMillis(500), rig.finder.requestedTimeout);
            char[] password = "temporary".toCharArray();
            done(rig.service.register(rig.info, "alice", password));
            assertEquals(1, rig.auth.registrations.get()); assertEquals("localhost", rig.auth.host);
            assertEquals(rig.info.tlsPort(), rig.auth.port); assertEquals("alice", rig.auth.username);
            assertEquals("\0".repeat(password.length), new String(password));
            assertTrue(allZero(rig.auth.suppliedPassword));
        }
    }

    @Test void completeQuizUsesActualTcpAndAuthoritativeResults() throws Exception {
        try (Rig rig = new Rig(MockQuizDemo::play)) {
            List<GameStatus> transitions = new java.util.concurrent.CopyOnWriteArrayList<>();
            rig.service.state().addObserver(s -> transitions.add(s.status()));
            char[] password = "demo-only".toCharArray();
            done(rig.service.login(rig.info, "alice", password));
            assertTrue(allZero(password)); assertTrue(allZero(rig.auth.suppliedPassword));
            assertEquals(1, rig.auth.logins.get()); assertEquals(GameStatus.LOBBY, rig.service.state().snapshot().status());
            done(rig.service.listRooms()); ClientTestSupport.await(rig.service.state(), s -> s.availableRooms().size() == 1);
            done(rig.service.createRoom("Networks", 2));
            status(rig, GameStatus.WAITING_ROOM);
            ClientTestSupport.await(rig.service.state(), s -> s.knownPlayers().contains("bob"));
            done(rig.service.startGame()); status(rig, GameStatus.QUESTION_ACTIVE);
            assertThrows(ExecutionException.class, () -> done(rig.service.submitAnswer("old-question", 0)));
            rig.feed.countdown("r1", 0);
            ClientTestSupport.await(rig.service.state(), s -> Integer.valueOf(0).equals(s.remainingSeconds()));
            done(rig.service.submitAnswer("q1", 1));
            ClientTestSupport.await(rig.service.state(), s -> s.question() != null && s.question().id().equals("q2"));
            assertEquals(130, rig.service.state().snapshot().totalScore());
            done(rig.service.submitAnswer("q2", 0));
            var finalState = status(rig, GameStatus.GAME_OVER);
            assertEquals("alice", finalState.finalResult().winner()); assertEquals(230, finalState.totalScore());
            assertEquals(2, finalState.finalResult().standings().size());
            rig.feed.leaderboard("r1", Map.of("alice", 9999)); rig.feed.countdown("r1", 10);
            done(rig.service.ping()); ClientTestSupport.await(rig.service.state(), s -> s.lastPong() != null);
            assertEquals(230, rig.service.state().snapshot().leaderboard().get(0).score());
            assertTrue(transitions.containsAll(List.of(GameStatus.CONNECTING, GameStatus.AUTHENTICATING,
                    GameStatus.LOBBY, GameStatus.WAITING_ROOM, GameStatus.QUESTION_ACTIVE,
                    GameStatus.ANSWER_SUBMITTED, GameStatus.ROUND_RESULT, GameStatus.GAME_OVER)));
            done(rig.service.leaveRoom()); status(rig, GameStatus.LOBBY);
            assertNull(rig.service.state().snapshot().roomId());
            // Drain the requested ROOMS reply before closing, avoiding a Windows TCP reset with unread data.
            ClientTestSupport.await(rig.service.state(), s -> "Room list updated.".equals(s.notice()));
            done(rig.service.disconnect()); assertTrue(rig.feed.closed); rig.server.awaitScript();
        }
    }

    @Test void duplicateSubmissionsArePreventedAndBadMessageAllowsRetry() throws Exception {
        CountDownLatch firstSeen = new CountDownLatch(1); CountDownLatch reject = new CountDownLatch(1);
        try (Rig rig = new Rig((socket, in) -> {
            handshake(socket, in); assertEquals("JOIN|r1", in.readLine());
            FakeGameServer.write(socket, "JOINED|r1|Networks|false\n" + MockQuizDemo.question(1));
            assertEquals("ANSWER|q1|1", in.readLine()); firstSeen.countDown();
            assertTrue(reject.await(3, TimeUnit.SECONDS));
            FakeGameServer.write(socket, "ERROR|BAD_MESSAGE|Malformed fixture request\n");
            assertEquals("ANSWER|q1|2", in.readLine());
            FakeGameServer.write(socket, "RESULT|q1|1|0|0\n"); assertNull(in.readLine());
        })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)); done(rig.service.joinRoom("r1"));
            status(rig, GameStatus.QUESTION_ACTIVE);
            done(rig.service.submitAnswer("q1", 1)); assertTrue(firstSeen.await(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> done(rig.service.submitAnswer("q1", 2)));
            reject.countDown(); status(rig, GameStatus.QUESTION_ACTIVE);
            done(rig.service.submitAnswer("q1", 2)); status(rig, GameStatus.ROUND_RESULT);
            done(rig.service.disconnect()); rig.server.awaitScript();
        }
    }

    @Test void nonHostCannotStartAndRoomErrorRemainsRecoverable() throws Exception {
        try (Rig rig = new Rig((socket, in) -> {
            handshake(socket, in); assertEquals("JOIN|missing", in.readLine());
            FakeGameServer.write(socket, "ERROR|ROOM_NOT_FOUND|No room\n");
            assertEquals("JOIN|r1", in.readLine()); FakeGameServer.write(socket, "JOINED|r1|Networks|false\n");
            assertEquals("PING", in.readLine()); FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)); done(rig.service.joinRoom("missing"));
            ClientTestSupport.await(rig.service.state(), s -> "ROOM_NOT_FOUND".equals(s.errorCode()));
            done(rig.service.joinRoom("r1")); status(rig, GameStatus.WAITING_ROOM);
            assertThrows(ExecutionException.class, () -> done(rig.service.startGame()));
            done(rig.service.ping()); ClientTestSupport.await(rig.service.state(), s -> s.lastPong() != null);
            assertTrue(rig.service.state().snapshot().authenticated());
            done(rig.service.disconnect()); rig.server.awaitScript();
        }
    }

    @Test void udpCallbacksAreDefensivelyCopiedFilteredAndOrderedWithTcp() throws Exception {
        try (Rig rig = new Rig((socket, in) -> {
            handshake(socket, in); assertEquals("JOIN|r1", in.readLine());
            FakeGameServer.write(socket, "JOINED|r1|Networks|false\n" + MockQuizDemo.question(1));
            assertEquals("PING", in.readLine()); FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)); done(rig.service.joinRoom("r1")); status(rig, GameStatus.QUESTION_ACTIVE);
            Map<String, Integer> scores = new HashMap<>(Map.of("bob", 90, "alice", 100));
            rig.feed.leaderboard("r1", scores); scores.put("alice", 999);
            rig.feed.countdown("r1", 8); rig.feed.countdown("other", 1); rig.feed.leaderboard("other", Map.of("alice", 999));
            done(rig.service.ping()); ClientTestSupport.await(rig.service.state(), s -> s.lastPong() != null);
            assertEquals(8, rig.service.state().snapshot().remainingSeconds());
            assertEquals(100, rig.service.state().snapshot().leaderboard().get(0).score());
            assertEquals(0, rig.service.state().snapshot().totalScore());
            done(rig.service.disconnect());
            rig.feed.countdown("r1", 1); rig.feed.leaderboard("r1", Map.of("alice", 10000));
            assertEquals(GameStatus.DISCONNECTED, rig.service.state().snapshot().status()); assertTrue(rig.feed.closed);
            rig.server.awaitScript();
        }
    }

    @Test void missingOrFailedLiveFeedDoesNotEndTcpSession() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { handshake(socket, in); assertNull(in.readLine()); })) {
            rig.feed.failStart = true; done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN));
            assertTrue(rig.feed.closed); assertTrue(rig.service.state().snapshot().authenticated());
            assertTrue(rig.service.state().snapshot().notice().contains("Live feed unavailable"));
            done(rig.service.disconnect()); rig.server.awaitScript();
        }
    }

    @Test void malformedNumericServerDataIsReportedWithoutLosingValidState() throws Exception {
        try (Rig rig = new Rig((socket, in) -> {
            handshake(socket, in); assertEquals("LIST_ROOMS", in.readLine());
            FakeGameServer.write(socket, "ROOMS|r1:Networks:not-an-integer:LOBBY\n");
            assertEquals("LIST_ROOMS", in.readLine()); FakeGameServer.write(socket, "ROOMS|\n"); assertNull(in.readLine());
        })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)); done(rig.service.listRooms());
            ClientTestSupport.await(rig.service.state(), s -> "BAD_SERVER_MESSAGE".equals(s.errorCode()));
            assertTrue(rig.service.state().snapshot().authenticated());
            done(rig.service.listRooms()); ClientTestSupport.await(rig.service.state(), s -> s.errorCode() == null);
            done(rig.service.disconnect()); rig.server.awaitScript();
        }
    }

    @Test void serverShutdownClearsStaleStateAndClosesFeed() throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);
        try (Rig rig = new Rig((socket, in) -> {
            handshake(socket, in); assertEquals("JOIN|r1", in.readLine());
            FakeGameServer.write(socket, "JOINED|r1|Networks|true\n" + MockQuizDemo.question(1));
            assertTrue(shutdown.await(3, TimeUnit.SECONDS));
        })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)); done(rig.service.joinRoom("r1")); status(rig, GameStatus.QUESTION_ACTIVE);
            shutdown.countDown(); status(rig, GameStatus.DISCONNECTED);
            assertNull(rig.service.state().snapshot().question()); assertTrue(rig.feed.closed);
            assertTrue(rig.service.state().snapshot().notice().contains("connection lost")); rig.server.awaitScript();
        }
    }

    @Test void authenticationTimeoutIsReportedAndCanBeCancelledCleanly() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { in.readLine(); assertNull(in.readLine()); }, new ClientTimeouts(500, 150, 0))) {
            assertThrows(ExecutionException.class, () -> done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)));
            status(rig, GameStatus.DISCONNECTED);
            assertTrue(rig.service.state().snapshot().notice().contains("timed out")); rig.server.awaitScript();
        }
    }

    @Test void disconnectInterruptsConnectionSetupWithoutWaitingForAuthenticationTimeout() throws Exception {
        CountDownLatch hello = new CountDownLatch(1);
        try (Rig rig = new Rig((socket, in) -> { in.readLine(); hello.countDown(); assertNull(in.readLine()); })) {
            var connection = rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN);
            assertTrue(hello.await(3, TimeUnit.SECONDS));
            done(rig.service.disconnect()); assertTrue(connection.isCompletedExceptionally());
            assertEquals(GameStatus.DISCONNECTED, rig.service.state().snapshot().status()); rig.server.awaitScript();
        }
    }

    @Test void failedTlsCallsDoNotExposeCredentialsOrUpstreamDescriptions() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { })) {
            rig.auth.reject = true; char[] password = "private-password".toCharArray();
            ExecutionException loginError = assertThrows(ExecutionException.class, () -> done(rig.service.login(rig.info, "alice", password)));
            assertFalse(loginError.toString().contains("private-password"));
            assertFalse(rig.service.state().snapshot().notice().contains("Untrusted upstream"));
            assertTrue(allZero(password)); assertTrue(allZero(rig.auth.suppliedPassword));
            char[] registration = "private-password".toCharArray();
            assertThrows(ExecutionException.class, () -> done(rig.service.register(rig.info, "alice", registration)));
            assertTrue(allZero(registration)); assertTrue(allZero(rig.auth.suppliedPassword));
        }
    }

    @Test void rejectsInvalidOperationsAndClosesAllWorkers() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { })) {
            assertThrows(ExecutionException.class, () -> done(rig.service.submitAnswer("q1", 1)));
            assertThrows(ExecutionException.class, () -> done(rig.service.discoverServers(Duration.ZERO)));
            char[] password = "nonempty".toCharArray();
            assertThrows(ExecutionException.class, () -> done(rig.service.login(rig.info, "bad:name", password)));
            assertTrue(allZero(password));
            rig.service.close(); rig.service.close();
            assertTrue(rig.service.awaitTermination(Duration.ofSeconds(2)));
            assertThrows(ExecutionException.class, () -> done(rig.service.ping()));
            char[] afterClose = "temporary".toCharArray();
            assertThrows(ExecutionException.class, () -> done(rig.service.register(rig.info, "alice", afterClose)));
            assertTrue(allZero(afterClose));
        }
    }

    @Test void rejectsDuplicateConnectWithoutReplacingExistingSession() throws Exception {
        try (Rig rig = new Rig((socket, in) -> { handshake(socket, in); assertNull(in.readLine()); })) {
            done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN));
            assertThrows(ExecutionException.class, () -> done(rig.service.connect(rig.info, FakeSecureLoginClient.TOKEN)));
            assertTrue(rig.service.state().snapshot().authenticated()); done(rig.service.disconnect()); rig.server.awaitScript();
        }
    }

    @Test void staleCallbacksFromPreviousReceiverCannotUpdateNewSession() throws Exception {
        FakeLiveFeedReceiver firstFeed = new FakeLiveFeedReceiver(); FakeLiveFeedReceiver secondFeed = new FakeLiveFeedReceiver();
        java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        FakeGameServer.Script script = (socket, in) -> {
            handshake(socket, in); assertEquals("JOIN|r1", in.readLine());
            FakeGameServer.write(socket, "JOINED|r1|Networks|false\n" + MockQuizDemo.question(1));
            String line;
            while ((line = in.readLine()) != null) {
                assertEquals("PING", line); FakeGameServer.write(socket, "PONG\n");
            }
        };
        try (FakeGameServer first = new FakeGameServer(script); FakeGameServer second = new FakeGameServer(script);
             GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(),
                     () -> connections.getAndIncrement() == 0 ? firstFeed : secondFeed)) {
            done(service.connect(new ServerInfo("First", "localhost", first.port(), first.port()), FakeSecureLoginClient.TOKEN));
            done(service.joinRoom("r1")); ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.QUESTION_ACTIVE);
            done(service.disconnect()); first.awaitScript();
            done(service.connect(new ServerInfo("Second", "localhost", second.port(), second.port()), FakeSecureLoginClient.TOKEN));
            done(service.joinRoom("r1")); ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.QUESTION_ACTIVE);
            firstFeed.countdown("r1", 1); firstFeed.leaderboard("r1", Map.of("alice", 999));
            done(service.ping()); ClientTestSupport.await(service.state(), s -> s.lastPong() != null);
            assertEquals(15, service.state().snapshot().remainingSeconds()); assertTrue(service.state().snapshot().leaderboard().isEmpty());
            secondFeed.countdown("r1", 7); ClientTestSupport.await(service.state(), s -> Integer.valueOf(7).equals(s.remainingSeconds()));
            done(service.disconnect()); second.awaitScript(); service.close();
            assertTrue(service.awaitTermination(Duration.ofSeconds(2))); assertTrue(firstFeed.closed); assertTrue(secondFeed.closed);
        }
    }

    @Test void closeCompletesPendingLoginAndClearsItsPassword() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<char[]> retained = new java.util.concurrent.atomic.AtomicReference<>();
        com.quizbattle.common.SecureLoginClient auth = new com.quizbattle.common.SecureLoginClient() {
            @Override public void register(String host, int port, String user, char[] password) { }
            @Override public String login(String host, int port, String user, char[] password) throws IOException {
                retained.set(password); entered.countDown();
                try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled fake login"); }
                throw new IOException("Fake login deadline expired");
            }
        };
        try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth, null)) {
            char[] password = "temporary".toCharArray();
            var pending = service.login(new ServerInfo("Fixture", "localhost", 1, 2), "alice", password);
            assertTrue(entered.await(2, TimeUnit.SECONDS)); service.close();
            assertThrows(ExecutionException.class, () -> done(pending));
            assertTrue(service.awaitTermination(Duration.ofSeconds(2))); assertTrue(allZero(password)); assertTrue(allZero(retained.get()));
        }
    }

    private static boolean allZero(char[] password) { return password != null && new String(password).equals("\0".repeat(password.length)); }
}
