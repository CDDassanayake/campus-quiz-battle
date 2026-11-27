package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.service.AuthenticationFailure;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.UiStateSubscription;
import com.quizbattle.client.ui.FormValidation;
import com.quizbattle.common.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class Phase7ReliabilityTest {
    @TempDir Path temporary;
    private static <T> T done(CompletableFuture<T> f) throws Exception { return f.get(4, TimeUnit.SECONDS); }
    @ParameterizedTest @ValueSource(strings = {"localhost:5050", "[::1]:5050", "host:service"})
    void manualHostRejectsEmbeddedPortWithoutDoingDns(String host) {
        assertThrows(IllegalArgumentException.class, () -> FormValidation.host(host));
    }
    @ParameterizedTest @ValueSource(strings = {"fe80::1%4", "::ffff:127.0.0.1"})
    void manualHostRetainsScopedAndMappedIpv6Syntax(String host) {
        assertEquals(host, FormValidation.host(host));
    }
    @ParameterizedTest @ValueSource(strings = {"BAD_CREDENTIALS", "USER_EXISTS", "LOCKED", "BAD_MESSAGE"})
    void actualM5ErrorEnvelopeMapsOnlyItsCodeAndDropsAllText(String code) {
        var failure = AuthenticationFailure.from(new IOException("Server error: " + code + " - private password/token text"), false);
        assertEquals(code, failure.code()); assertFalse(failure.getMessage().contains("private")); assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings = {"prefix Server error: LOCKED - secret", "Server error: NEW_CODE - LOCKED", "other BAD_CREDENTIALS", "Server error: USER_EXISTS_suffix - secret"})
    void arbitraryUpstreamTextCannotMasqueradeAsKnownAuthenticationCode(String text) {
        assertEquals("AUTH_UNAVAILABLE", AuthenticationFailure.from(new IOException(text), true).code());
    }
    @ParameterizedTest @ValueSource(strings = {"certificate", "missing", "timeout", "refused", "hostname"})
    void typedFailuresExposeActionableLocalMessagesWithoutSensitiveCauses(String kind) {
        Exception error = switch (kind) {
            case "certificate" -> new javax.net.ssl.SSLHandshakeException("private cert path");
            case "missing" -> new NoSuchFileException("private cert path");
            case "timeout" -> new java.net.SocketTimeoutException("private host");
            case "refused" -> new java.net.ConnectException("private host");
            default -> new java.net.UnknownHostException("private host");
        };
        var failure = AuthenticationFailure.from(new IOException("untrusted wrapper", error), false);
        assertEquals(switch (kind) { case "certificate" -> "TLS_CERTIFICATE"; case "missing" -> "CERTIFICATE_FILE";
            case "timeout" -> "AUTH_TIMEOUT"; case "refused" -> "AUTH_CONNECTION"; default -> "AUTH_HOST"; }, failure.code());
        assertFalse(failure.getMessage().contains("private")); assertFalse(failure.getMessage().contains("untrusted")); assertNull(failure.getCause());
    }
    private AppConfig config(String text) throws Exception { Path file = temporary.resolve("client.properties"); Files.writeString(file, text); return AppConfig.load(file.toString()); }
    @Test void transportTimeoutsLoadFromExistingConfigWithoutChangingSharedFiles() throws Exception {
        assertEquals(ClientTimeouts.defaults(), ClientTimeouts.fromConfig(config("")));
        assertEquals(new ClientTimeouts(2200, 3500, 12000), ClientTimeouts.fromConfig(config("client.connect.timeout.ms=2200\nclient.authentication.timeout.ms=3500\nclient.read.timeout.ms=12000\n")));
    }
    @ParameterizedTest @ValueSource(strings = {"client.connect.timeout.ms=0", "client.authentication.timeout.ms=-1", "client.read.timeout.ms=-1", "client.connect.timeout.ms=private-value", "client.read.timeout.ms=99999999999"})
    void invalidTimeoutSettingsFailEarlyWithoutEchoingValues(String property) throws Exception {
        var config = config(property); var error = assertThrows(IllegalArgumentException.class, () -> ClientTimeouts.fromConfig(config));
        assertFalse(error.getMessage().contains("private-value"));
    }
    private static ClientGameState question() {
        var state = new ClientGameState(); state.connecting(new ServerInfo("Fixture", "localhost", 1, 2));
        state.receive(MessageCodec.decode("WELCOME|alice")); state.receive(MessageCodec.decode("JOINED|r1|Networks|true"));
        state.receive(MessageCodec.decode("QUESTION|q1|1|2|Question|A|B|C|D|15")); return state;
    }
    @Test void burstOfFeedUpdatesUsesOneUiTaskAndKeepsOnlyLatestAdjacentVisualSnapshot() {
        var state = question(); var queue = new ArrayList<Runnable>(); var delivered = new ArrayList<ClientGameState.Snapshot>();
        try (var subscription = new UiStateSubscription(state, queue::add, delivered::add, true)) {
            for (int i = 0; i < 2000; i++) { state.leaderboard("r1", Map.of("alice", i)); }
            assertEquals(1, queue.size()); queue.remove(0).run(); assertEquals(1, delivered.size());
            assertEquals(1999, delivered.get(0).leaderboard().get(0).score()); assertEquals(0, delivered.get(0).totalScore());
        }
    }
    @Test void coalescingPreservesAnswerResultQuestionAndFinalBoundariesInOrder() {
        var state = question(); var queue = new ArrayList<Runnable>(); var delivered = new ArrayList<GameStatus>();
        try (var subscription = new UiStateSubscription(state, queue::add, s -> delivered.add(s.status()), true)) {
            state.leaderboard("r1", Map.of("alice", 0)); state.answerSubmitted("q1", 1);
            state.receive(MessageCodec.decode("RESULT|q1|1|100|100")); state.leaderboard("r1", Map.of("alice", 100));
            state.receive(MessageCodec.decode("QUESTION|q2|2|2|Next|A|B|C|D|15"));
            state.receive(MessageCodec.decode("GAME_OVER|alice|alice:100"));
            while (!queue.isEmpty()) { queue.remove(0).run(); }
            assertEquals(List.of(GameStatus.QUESTION_ACTIVE, GameStatus.ANSWER_SUBMITTED, GameStatus.ROUND_RESULT, GameStatus.QUESTION_ACTIVE, GameStatus.GAME_OVER), delivered);
        }
    }
    @Test void coalescedSubscriptionDropsDisposedWorkAndYieldsBetweenLargeCriticalBatches() {
        var state = question(); var queue = new ArrayList<Runnable>(); var delivered = new ArrayList<String>();
        var subscription = new UiStateSubscription(state, queue::add, s -> delivered.add(s.notice()), true);
        for (int i = 0; i < 100; i++) { state.notification("Notice " + i); }
        assertEquals(1, queue.size()); queue.remove(0).run(); assertEquals(64, delivered.size()); assertEquals(1, queue.size());
        subscription.close(); queue.remove(0).run(); assertEquals(64, delivered.size()); state.notification("Disposed"); assertTrue(queue.isEmpty());
    }
    @Test void aFailingUiCallbackCannotWedgeTheCoalescedDeliveryQueue() {
        var state = question(); var queue = new ArrayList<Runnable>(); var delivered = new ArrayList<String>();
        try (var subscription = new UiStateSubscription(state, queue::add, s -> {
            if (s.notice().equals("Choose an answer.")) { throw new IllegalStateException("View failed"); }
            delivered.add(s.notice());
        }, true)) {
            state.notification("Recovery snapshot");
            assertThrows(IllegalStateException.class, () -> queue.remove(0).run());
            assertEquals(1, queue.size()); queue.remove(0).run(); assertEquals(List.of("Recovery snapshot"), delivered);
        }
    }
    @Test void largeRankingRemainsInformationalSortedAndImmutable() {
        var state = question(); var scores = new HashMap<String, Integer>();
        for (int i = 0; i < 1000; i++) { scores.put(String.format(Locale.ROOT, "user%04d", i), i); }
        state.leaderboard("r1", scores); var snapshot = state.snapshot(); assertEquals(1000, snapshot.leaderboard().size());
        assertEquals("user0999", snapshot.leaderboard().get(0).username()); assertEquals(0, snapshot.totalScore());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.leaderboard().clear()); scores.clear(); assertEquals(1000, snapshot.leaderboard().size());
    }
    @Test void timedOutRoomRefreshReleasesTheSharedWaitAndAllowsARealRetry() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, input) -> {
            assertTrue(input.readLine().startsWith("HELLO|")); FakeGameServer.write(socket, "WELCOME|alice\n");
            assertEquals("LIST_ROOMS", input.readLine()); // Deliberately no reply.
            assertEquals("LIST_ROOMS", input.readLine()); FakeGameServer.write(socket, "ROOMS|r1:Networks:2:LOBBY\n"); assertNull(input.readLine());
        }); GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(), null, ClientTimeouts.defaults(), Duration.ofMillis(300))) {
            done(service.connect(new ServerInfo("Fixture", "localhost", server.port(), server.port()), FakeSecureLoginClient.TOKEN));
            var first = service.refreshRooms(); var shared = service.refreshRooms();
            assertTrue(assertThrows(ExecutionException.class, () -> done(first)).getCause().getMessage().contains("timed out"));
            assertThrows(ExecutionException.class, () -> done(shared)); assertTrue(service.state().snapshot().authenticated());
            assertEquals(1, done(service.refreshRooms()).size()); done(service.disconnect()); server.awaitScript();
            service.close(); assertTrue(service.awaitTermination(Duration.ofSeconds(2)));
        }
    }
    @Test void twoCompleteGamesAcrossLoginLogoutIgnoreThePriorFeedAndResetScores() throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient();
        try (GuiMockScenario scenario = new GuiMockScenario(auth, false);
             FakeGameServer server = new FakeGameServer(scenario::play, 2);
             GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth,
                     () -> { var feed = new FakeLiveFeedReceiver(); scenario.feed = feed; return feed; })) {
            scenario.fullGame = true; scenario.automatic = false;
            var info = new ServerInfo("Fixture", "localhost", server.port(), server.port());
            FakeLiveFeedReceiver retired = null;
            for (int game = 0; game < 2; game++) {
                done(service.login(info, "alice", new char[]{'x'})); done(service.createRoom("Consecutive", 3));
                ClientTestSupport.await(service.state(), s -> s.host()); done(service.startGame());
                var q1 = ClientTestSupport.await(service.state(), s -> s.question() != null).question();
                assertEquals(0, service.state().snapshot().totalScore());
                if (retired != null) {
                    assertTrue(retired.closed); assertNotSame(retired, scenario.feed);
                    retired.countdown("r5", 0); retired.leaderboard("r5", Map.of("intruder", 99999));
                    done(service.ping()); ClientTestSupport.await(service.state(), s -> s.lastPong() != null);
                    assertEquals(15, service.state().snapshot().remainingSeconds());
                    assertFalse(service.state().snapshot().leaderboard().stream().anyMatch(p -> p.username().equals("intruder")));
                }
                done(service.submitAnswer(q1, 1)); assertEquals(1, scenario.receivedAnswers.poll(2, TimeUnit.SECONDS)); scenario.finishRound();
                ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT); scenario.nextQuestion();
                var q2 = ClientTestSupport.await(service.state(), s -> s.question() != null && s.question().number() == 2).question();
                done(service.submitAnswer(q2, 0)); assertEquals(0, scenario.receivedAnswers.poll(2, TimeUnit.SECONDS)); scenario.finishRound();
                ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT); scenario.nextQuestion();
                ClientTestSupport.await(service.state(), s -> s.question().number() == 3); scenario.finishRound();
                ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT); scenario.nextQuestion();
                var finished = ClientTestSupport.await(service.state(), s -> s.finalResult() != null);
                assertEquals(107, finished.totalScore()); assertEquals("bob", finished.finalResult().winner());
                done(service.leaveRoom()); ClientTestSupport.await(service.state(), s -> "Room list updated.".equals(s.notice()));
                retired = scenario.feed; done(service.disconnect());
                assertNull(service.state().snapshot().question()); assertFalse(service.state().snapshot().authenticated());
            }
            server.awaitScript();
        }
    }
}
