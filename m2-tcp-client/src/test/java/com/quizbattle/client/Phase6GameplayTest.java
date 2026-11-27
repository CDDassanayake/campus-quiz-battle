package com.quizbattle.client;

import com.quizbattle.client.model.*;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.*;
import com.quizbattle.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class Phase6GameplayTest {
    private static void receive(ClientGameState state, String wire) { assertTrue(state.receive(MessageCodec.decode(wire))); }
    private static ClientGameState question() {
        var state = new ClientGameState(); state.connecting(new ServerInfo("Mock", "localhost", 1, 2));
        receive(state, "WELCOME|alice"); receive(state, "JOINED|r1|Networks|true");
        receive(state, "QUESTION|arbitrary-id|2|5|A long question that wraps across the interface|Alpha|Beta|Gamma|Delta|15"); return state;
    }
    private static <T> T done(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }

    @ParameterizedTest @CsvSource({"DIGIT1,0", "DIGIT2,1", "DIGIT3,2", "DIGIT4,3", "NUMPAD1,0", "NUMPAD2,1", "NUMPAD3,2", "NUMPAD4,3", "SPACE,-1", "ENTER,-1"})
    void keyboardMappingAndHeldKeyLatch(String key, int expected) {
        assertEquals(expected, AnswerKeys.choice(key)); var keys = new AnswerKeys();
        assertEquals(expected >= 0, keys.press(key)); assertFalse(keys.press(key));
        keys.release(key); assertEquals(expected >= 0, keys.press(key));
    }
    @Test void questionPresentationUsesActualIdProgressOptionsAndUnboundedText() {
        var s = question(); var q = s.snapshot().question();
        assertEquals("arbitrary-id", q.id()); assertEquals(2, q.number()); assertEquals(5, q.total());
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"), q.options());
        assertTrue(QuizPresentation.canAnswer(s.snapshot(), q));
        var longQ = new Question("long", 1, 1, "Network question ".repeat(100), Collections.nCopies(4, "Answer ".repeat(80)), 1);
        assertEquals(1700, longQ.text().length()); assertEquals(560, longQ.options().get(0).length());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void selectionMappingPendingAndNewQuestionReset(int index) {
        var s = question(); var old = s.snapshot().question(); s.answerSubmitted(old.id(), index);
        assertEquals(index, s.snapshot().selectedAnswer()); assertFalse(QuizPresentation.canAnswer(s.snapshot(), old));
        assertTrue(QuizPresentation.answer(old, index).startsWith("" + "ABCD".charAt(index)));
        assertTrue(QuizPresentation.submission(s.snapshot(), false).startsWith("Answer sent."));
        assertThrows(IllegalStateException.class, () -> s.answerSubmitted(old.id(), index));
        receive(s, "RESULT|arbitrary-id|1|7|107");
        receive(s, "QUESTION|arbitrary-id|3|5|New question|One|Two|Three|Four|20");
        assertNull(s.snapshot().selectedAnswer()); assertNull(s.snapshot().roundResult()); assertEquals(107, s.snapshot().totalScore());
        assertEquals(20, s.snapshot().remainingSeconds()); assertFalse(QuizPresentation.canAnswer(s.snapshot(), old));
        assertTrue(QuizPresentation.canAnswer(s.snapshot(), s.snapshot().question()));
    }
    @ParameterizedTest @CsvSource({"0,Incorrect selection", "1,Correct selection", "2,Incorrect selection", "3,Incorrect selection"})
    void roundFeedbackUsesServerValuesEvenWhenIncorrectAwardsPoints(int index, String outcome) {
        var s = question(); s.answerSubmitted("arbitrary-id", index);
        receive(s, "RESULT|unrelated|1|900|900"); assertNull(s.snapshot().roundResult());
        receive(s, "RESULT|arbitrary-id|1|7|107");
        assertEquals(outcome, QuizPresentation.outcome(s.snapshot()));
        assertEquals(7, s.snapshot().roundResult().pointsAwarded()); assertEquals(107, s.snapshot().totalScore());
    }
    @Test void unansweredRoundStillUsesActualAwardedPoints() {
        var s = question(); receive(s, "RESULT|arbitrary-id|2|9|19");
        assertEquals("Unanswered", QuizPresentation.outcome(s.snapshot())); assertEquals(19, s.snapshot().totalScore());
        assertEquals("No answer submitted locally", QuizPresentation.answer(s.snapshot().question(), null));
    }
    @ParameterizedTest @ValueSource(strings = {"ALREADY_ANSWERED", "TOO_LATE", "NOT_IN_ROOM", "GAME_RUNNING", "BAD_MESSAGE"})
    void recoverableRejectionFollowsExistingRetryPolicy(String code) {
        var s = question(); s.answerSubmitted("arbitrary-id", 0); receive(s, "ERROR|" + code + "|untrusted upstream description");
        assertTrue(s.snapshot().authenticated()); assertFalse(s.snapshot().notice().contains("untrusted"));
        assertTrue(QuizPresentation.submission(s.snapshot(), false).contains(code));
        assertEquals("Sending answer…", QuizPresentation.submission(s.snapshot(), true));
        assertEquals(code.equals("BAD_MESSAGE"), QuizPresentation.canAnswer(s.snapshot(), s.snapshot().question()));
    }
    @Test void countdownFiltersAndZeroNeverDecidesAnswerEligibility() {
        var s = question(); assertEquals(15, s.snapshot().remainingSeconds());
        s.countdown("wrong", 5); s.countdown("r1", -1); s.countdown("r1", 16); assertEquals(15, s.snapshot().remainingSeconds());
        s.countdown("r1", 7); s.countdown("r1", 8); assertEquals(7, s.snapshot().remainingSeconds());
        s.countdown("r1", 0); assertTrue(QuizPresentation.canAnswer(s.snapshot(), s.snapshot().question()));
        assertEquals(GameStatus.QUESTION_ACTIVE, s.snapshot().status()); assertNull(s.snapshot().roundResult());
        receive(s, "RESULT|arbitrary-id|0|0|0"); long version = s.snapshot().version();
        s.countdown("r1", 0); assertEquals(version, s.snapshot().version());
    }
    @Test void rankingFreshnessDetectsMissingIdenticalFreshAndStaleFeeds() {
        var s = question(); assertTrue(RankingPresentation.freshness(0, 0, 0).contains("Waiting"));
        s.leaderboard("wrong", Map.of("bob", 999)); assertEquals(0, s.snapshot().leaderboardRevision());
        s.leaderboard("r1", Map.of("bob", 20, "alice", 20, "charlie", 30)); var before = s.snapshot();
        assertEquals(List.of("charlie", "alice", "bob"), before.leaderboard().stream().map(PlayerScore::username).toList());
        s.leaderboard("r1", Map.of("bob", 20, "alice", 20, "charlie", 30)); assertEquals(2, s.snapshot().leaderboardRevision());
        assertTrue(RankingPresentation.freshness(1, 10, 10).contains("Latest"));
        assertTrue(RankingPresentation.freshness(1, 10, 6_000_000_010L).contains("No recent"));
        s.leaveLocally(); assertEquals(0, s.snapshot().leaderboardRevision());
        s.leaderboard("r1", Map.of("bob", 999)); assertTrue(s.snapshot().leaderboard().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"", "bob:20", "bob:20;alice:20", "bob:20;alice:20;charlie:30", "bob:20;alice:20;charlie:30;diana:5"})
    void finalResultsPreserveServerOrderWinnerAndTies(String payload) {
        var s = question(); receive(s, "GAME_OVER|" + (payload.isEmpty() ? "" : "bob") + "|" + payload);
        var result = s.snapshot().finalResult(); assertNotNull(result);
        assertEquals(payload.isEmpty() ? "" : "bob", result.winner());
        assertEquals(payload.isEmpty() ? 0 : payload.split(";").length, result.standings().size());
        if (!payload.isEmpty()) { assertEquals("bob", result.standings().get(0).username()); }
        s.leaderboard("r1", Map.of("eve", 999)); assertEquals(result.standings(), s.snapshot().leaderboard());
        String personal = RankingPresentation.personal(result.standings(), "alice");
        assertEquals(payload.contains("alice") ? "Your final score · 20   /   Server listing position 2" : "Your player is not listed in the final standings.", personal);
    }
    @Test void malformedFinalDoesNotReplaceQuestionAndDisconnectDisablesGameplay() {
        var s = question(); assertFalse(s.receive(MessageCodec.decode("GAME_OVER|bob|alice:20")));
        assertEquals(GameStatus.QUESTION_ACTIVE, s.snapshot().status());
        s.disconnected("Connection lost"); assertFalse(QuizPresentation.canAnswer(s.snapshot(), s.snapshot().question()));
        assertNull(s.snapshot().question());
    }
    @Test void rapidTransitionsAreNotHeldByPresentationAndLeaveClearsState() {
        var s = question(); receive(s, "RESULT|arbitrary-id|0|10|10");
        receive(s, "QUESTION|next|5|5|Last|A|B|C|D|15"); receive(s, "GAME_OVER|bob|bob:30;alice:10");
        assertEquals(GameStatus.GAME_OVER, s.snapshot().status()); assertEquals("bob", s.snapshot().finalResult().winner());
        s.leaveLocally(); assertTrue(s.snapshot().authenticated()); assertEquals(GameStatus.LOBBY, s.snapshot().status()); assertNull(s.snapshot().finalResult());
    }
    @Test void displayedQuestionOverloadRejectsOldInstanceWithReusedIdOverRealTcp() throws Exception {
        CountDownLatch next = new CountDownLatch(1);
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertTrue(in.readLine().startsWith("HELLO|")); FakeGameServer.write(socket, "WELCOME|alice\nJOINED|r1|Networks|true\nQUESTION|same|1|2|First|A|B|C|D|15\n");
            assertTrue(next.await(3, TimeUnit.SECONDS)); FakeGameServer.write(socket, "QUESTION|same|2|2|Second|A|B|C|D|15\n");
            assertEquals("ANSWER|same|3", in.readLine()); assertNull(in.readLine());
        }); GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(), null)) {
            done(service.connect(new ServerInfo("Mock", "localhost", server.port(), server.port()), FakeSecureLoginClient.TOKEN));
            var first = ClientTestSupport.await(service.state(), s -> s.question() != null).question(); next.countDown();
            var second = ClientTestSupport.await(service.state(), s -> s.question() != null && s.question().number() == 2).question();
            assertThrows(ExecutionException.class, () -> done(service.submitAnswer(first, 1)));
            done(service.submitAnswer(second, 3)); assertThrows(ExecutionException.class, () -> done(service.submitAnswer(second, 0)));
            done(service.disconnect()); server.awaitScript();
        }
    }
    @Test void completeThreeRoundMockUsesRealTcpAndActualFinalScores() throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient();
        try (GuiMockScenario scenario = new GuiMockScenario(auth, false);
             FakeGameServer server = new FakeGameServer(scenario::play);
             GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth, () -> { var feed = new FakeLiveFeedReceiver(); scenario.feed = feed; return feed; })) {
            scenario.fullGame = true; scenario.automatic = false;
            done(service.login(new ServerInfo("Mock", "localhost", server.port(), server.port()), "alice", new char[]{'x'}));
            done(service.createRoom("Networks", 4)); ClientTestSupport.await(service.state(), s -> s.host()); done(service.startGame());
            var q1 = ClientTestSupport.await(service.state(), s -> s.question() != null).question();
            done(service.submitAnswer(q1, 1)); assertEquals(1, scenario.receivedAnswers.poll(2, TimeUnit.SECONDS)); scenario.finishRound();
            ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT); scenario.nextQuestion();
            var q2 = ClientTestSupport.await(service.state(), s -> s.question() != null && s.question().number() == 2).question();
            done(service.submitAnswer(q2, 0)); assertEquals(0, scenario.receivedAnswers.poll(2, TimeUnit.SECONDS)); scenario.finishRound();
            assertEquals(107, ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT).totalScore());
            scenario.nextQuestion(); ClientTestSupport.await(service.state(), s -> s.question().number() == 3); scenario.finishRound();
            assertEquals("Unanswered", QuizPresentation.outcome(ClientTestSupport.await(service.state(), s -> s.status() == GameStatus.ROUND_RESULT)));
            scenario.nextQuestion(); assertEquals(4, ClientTestSupport.await(service.state(), s -> s.finalResult() != null).finalResult().standings().size());
            done(service.leaveRoom()); ClientTestSupport.await(service.state(), s -> s.availableRooms().size() == 4);
            assertEquals(2, scenario.answers.get()); done(service.disconnect()); server.awaitScript();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"QUESTION_ACTIVE", "ANSWER_SUBMITTED", "ROUND_RESULT", "GAME_OVER"})
    void tcpDisconnectClearsEachGameplayStage(String stage) throws Exception {
        CountDownLatch terminate = new CountDownLatch(1);
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertTrue(in.readLine().startsWith("HELLO|"));
            FakeGameServer.write(socket, "WELCOME|alice\nJOINED|r1|Networks|true\nQUESTION|q1|1|1|Question|A|B|C|D|15\n");
            if (!stage.equals("QUESTION_ACTIVE")) {
                assertEquals("ANSWER|q1|0", in.readLine());
                if (stage.equals("ROUND_RESULT")) { FakeGameServer.write(socket, "RESULT|q1|0|12|12\n"); }
                if (stage.equals("GAME_OVER")) { FakeGameServer.write(socket, "GAME_OVER|bob|bob:30;alice:12\n"); }
            }
            assertTrue(terminate.await(3, TimeUnit.SECONDS));
        }); GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(), null)) {
            done(service.connect(new ServerInfo("Mock", "localhost", server.port(), server.port()), FakeSecureLoginClient.TOKEN));
            var q = ClientTestSupport.await(service.state(), s -> s.question() != null).question();
            if (!stage.equals("QUESTION_ACTIVE")) { done(service.submitAnswer(q, 0)); }
            ClientTestSupport.await(service.state(), s -> s.status().name().equals(stage)); terminate.countDown();
            var lost = ClientTestSupport.await(service.state(), s -> !s.authenticated());
            assertNull(lost.question()); assertNull(lost.finalResult()); assertTrue(lost.leaderboard().isEmpty());
            assertThrows(ExecutionException.class, () -> done(service.submitAnswer(q, 0))); server.awaitScript();
        }
    }
    @Test void rejectedInvalidChoiceCannotWriteAndPendingUiScopeIgnoresDisposedCallbacks() throws Exception {
        var queued = new ArrayList<Runnable>(); var future = new CompletableFuture<Void>();
        var scope = new AsyncViewScope(queued::add, busy -> { });
        assertTrue(scope.run(() -> future, java.time.Duration.ofSeconds(2), v -> fail("Disposed success"), e -> fail("Disposed failure")));
        assertTrue(scope.busy()); scope.close(); future.complete(null); queued.forEach(Runnable::run); assertFalse(scope.busy());
        var s = question(); assertThrows(IllegalStateException.class, () -> s.answerSubmitted("arbitrary-id", 4));
        assertFalse(s.snapshot().answerSubmitted()); assertEquals("Sending answer…", QuizPresentation.submission(s.snapshot(), true));
    }
}
