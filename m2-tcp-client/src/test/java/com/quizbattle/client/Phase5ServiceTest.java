package com.quizbattle.client;

import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.service.AuthenticationFailure;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class Phase5ServiceTest {
    private static <T> T done(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void handshake(java.net.Socket socket, java.io.BufferedReader in) throws Exception {
        assertEquals("HELLO|" + FakeSecureLoginClient.TOKEN, in.readLine()); FakeGameServer.write(socket, "WELCOME|alice\n");
    }
    private static ServerInfo info(FakeGameServer server) { return new ServerInfo("Mock", "localhost", server.port(), server.port()); }
    private static GameClientService service(FakeSecureLoginClient auth) { return new GameClientService(new FakeServerFinder(List.of()), auth, FakeLiveFeedReceiver::new); }

    @Test void roomRefreshWaitsForResponseAndCoalescesDuplicateRequests() throws Exception {
        CountDownLatch received = new CountDownLatch(1); CountDownLatch reply = new CountDownLatch(1);
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            handshake(socket, in); assertEquals("LIST_ROOMS", in.readLine()); received.countDown(); assertTrue(reply.await(3, TimeUnit.SECONDS));
            FakeGameServer.write(socket, "ROOMS|r1:Networks:2:LOBBY\n"); assertEquals("PING", in.readLine());
            FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        }); GameClientService service = service(new FakeSecureLoginClient())) {
            done(service.connect(info(server), FakeSecureLoginClient.TOKEN));
            var first = service.refreshRooms(); assertTrue(received.await(2, TimeUnit.SECONDS)); assertFalse(first.isDone());
            var second = service.refreshRooms(); reply.countDown();
            assertEquals(done(first), done(second)); assertEquals(1, first.get().size());
            done(service.ping()); ClientTestSupport.await(service.state(), state -> state.lastPong() != null);
            done(service.disconnect()); server.awaitScript();
        }
    }

    @Test void malformedRoomsAndServerShutdownCompleteRefreshFutures() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            handshake(socket, in); assertEquals("LIST_ROOMS", in.readLine()); FakeGameServer.write(socket, "ROOMS|bad-row\n");
            assertEquals("LIST_ROOMS", in.readLine()); // Close during the second refresh.
        }); GameClientService service = service(new FakeSecureLoginClient())) {
            done(service.connect(info(server), FakeSecureLoginClient.TOKEN));
            assertThrows(ExecutionException.class, () -> done(service.refreshRooms()));
            assertTrue(service.state().snapshot().authenticated());
            assertThrows(ExecutionException.class, () -> done(service.refreshRooms()));
            ClientTestSupport.await(service.state(), state -> !state.authenticated()); server.awaitScript();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"USER_EXISTS", "BAD_MESSAGE", "LOCKED", "BAD_CREDENTIALS"})
    void authenticationErrorCodesAreSanitizedAndPasswordsAreCleared(String code) throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient(); auth.failureCode = code;
        try (GameClientService service = service(auth)) {
            char[] password = "private-password".toCharArray();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> done(service.register(new ServerInfo("Mock", "localhost", 1, 2), "alice", password)));
            AuthenticationFailure failure = assertInstanceOf(AuthenticationFailure.class, error.getCause());
            assertEquals(code, failure.code()); assertFalse(failure.toString().contains("Untrusted")); assertNull(failure.getCause());
            assertTrue(new String(password).chars().allMatch(c -> c == 0)); assertTrue(new String(auth.suppliedPassword).chars().allMatch(c -> c == 0));
            ExecutionException login = assertThrows(ExecutionException.class,
                    () -> done(service.login(new ServerInfo("Mock", "localhost", 1, 2), "alice", new char[] {'x'})));
            assertEquals(code, assertInstanceOf(AuthenticationFailure.class, login.getCause()).code());
        }
    }

    @Test void discoverySupportsEmptyFailureAndInFlightReuse() throws Exception {
        FakeServerFinder finder = new FakeServerFinder(List.of()); finder.gate = new CountDownLatch(1);
        GameClientService service = new GameClientService(finder, new FakeSecureLoginClient(), null);
        UiContext context = new UiContext(service, "MOCK");
        try {
            var first = context.discoverServers(); var second = context.discoverServers(); assertSame(first, second);
            finder.gate.countDown(); assertTrue(done(first).isEmpty()); assertEquals(1, finder.requests.get());
            finder.fail = true; assertThrows(ExecutionException.class, () -> done(context.discoverServers()));
            assertFalse(service.state().snapshot().authenticated());
        } finally { context.close(); assertTrue(context.awaitTermination(Duration.ofSeconds(2))); }
    }

    @Test void actualTcpSupportsCreateJoinRosterStartAndLocalLeave() throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient();
        try (GuiMockScenario scenario = new GuiMockScenario(auth, false);
             FakeGameServer server = new FakeGameServer(scenario::play, Integer.MAX_VALUE);
             GameClientService service = service(auth)) {
            done(service.login(info(server), "alice", new char[] {'x'})); assertEquals(4, done(service.refreshRooms()).size());
            done(service.createRoom("Taken", 5)); ClientTestSupport.await(service.state(), state -> "BAD_MESSAGE".equals(state.errorCode()));
            done(service.createRoom("Champions", 5));
            ClientTestSupport.await(service.state(), state -> state.knownPlayers().contains("bob") && !state.knownPlayers().contains("charlie"));
            assertTrue(service.state().snapshot().host()); done(service.startGame());
            ClientTestSupport.await(service.state(), state -> state.question() != null);
            assertEquals("q1", service.state().snapshot().question().id());
            done(service.leaveRoom()); assertEquals(GameStatus.LOBBY, service.state().snapshot().status());
            done(service.joinRoom("r1")); ClientTestSupport.await(service.state(), state -> state.status() == GameStatus.WAITING_ROOM);
            assertFalse(service.state().snapshot().host()); assertThrows(ExecutionException.class, () -> done(service.startGame()));
            scenario.firstQuestion(); ClientTestSupport.await(service.state(), state -> state.status() == GameStatus.QUESTION_ACTIVE);
            assertEquals(0, scenario.answers.get()); done(service.disconnect());
            assertNull(service.state().snapshot().question()); assertNull(service.state().snapshot().username());
        }
    }

    @Test void tokenFromLoginStillMustPassGatewayAuthentication() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertEquals("HELLO|" + FakeSecureLoginClient.TOKEN, in.readLine());
            FakeGameServer.write(socket, "ERROR|BAD_TOKEN|untrusted token text\n"); assertNull(in.readLine());
        }); GameClientService service = service(new FakeSecureLoginClient())) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> done(service.login(info(server), "alice", new char[] {'x'})));
            assertTrue(error.getCause().getMessage().contains("BAD_TOKEN")); assertFalse(error.toString().contains("untrusted token text"));
            assertFalse(service.state().snapshot().authenticated()); server.awaitScript();
        }
    }

    @Test void successfulSecureLoginDoesNotHideGameConnectionRefusal() throws Exception {
        try (java.net.Socket reservation = new java.net.Socket(); GameClientService service = service(new FakeSecureLoginClient())) {
            reservation.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("localhost"), 0));
            var server = new ServerInfo("Mock", "localhost", reservation.getLocalPort(), reservation.getLocalPort());
            ExecutionException error = assertThrows(ExecutionException.class, () -> done(service.login(server, "alice", new char[] {'x'})));
            assertTrue(error.getCause().getMessage().contains("refused")); assertEquals(GameStatus.DISCONNECTED, service.state().snapshot().status());
        }
    }

    @Test void lateSecureLoginCannotCreateAConnectionAfterUiLogout() throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient(); auth.loginGate = new CountDownLatch(1);
        var unexpected = new java.util.concurrent.atomic.AtomicInteger();
        try (FakeGameServer server = new FakeGameServer((socket, in) -> unexpected.incrementAndGet())) {
            GameClientService service = service(auth); UiContext context = new UiContext(service, "MOCK");
            try {
                var login = context.invoke(() -> service.login(info(server), "alice", new char[] {'x'}));
                assertTrue(auth.loginEntered.await(2, TimeUnit.SECONDS)); done(context.disconnect());
                auth.loginGate.countDown(); assertThrows(ExecutionException.class, () -> done(login));
            } finally { context.close(); assertTrue(context.awaitTermination(Duration.ofSeconds(2))); }
            assertEquals(0, unexpected.get()); assertFalse(service.state().snapshot().authenticated());
        }
    }
}
