package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.ServerInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ClientGameStateTest {
    private static ClientGameState lobby() {
        ClientGameState state = new ClientGameState();
        state.connecting(new ServerInfo("Fixture", "localhost", 1, 2)); state.authenticating();
        receive(state, "WELCOME|alice"); return state;
    }
    private static ClientGameState room() { ClientGameState state = lobby(); receive(state, "JOINED|r1|Networks|true"); return state; }
    private static ClientGameState question() { ClientGameState state = room(); receive(state, MockQuizDemo.question(1).stripTrailing()); return state; }
    private static void receive(ClientGameState state, String line) { assertTrue(state.receive(MessageCodec.decode(line))); }

    @Test void initialAndConnectionStatesAreObservable() {
        ClientGameState state = new ClientGameState();
        assertEquals(GameStatus.DISCONNECTED, state.snapshot().status());
        assertFalse(state.snapshot().authenticated()); assertTrue(state.snapshot().knownPlayers().isEmpty());
        List<GameStatus> seen = new ArrayList<>(); state.addObserver(s -> seen.add(s.status()));
        state.connecting(new ServerInfo("Fixture", "example.test", 1234, 2345));
        state.transportConnected(); receive(state, "WELCOME|alice");
        assertEquals(List.of(GameStatus.CONNECTING, GameStatus.AUTHENTICATING, GameStatus.LOBBY), seen);
        assertTrue(state.snapshot().connected()); assertTrue(state.snapshot().authenticated());
        assertEquals("alice", state.snapshot().username()); assertEquals("example.test", state.snapshot().serverHost());
        assertEquals(1234, state.snapshot().serverPort());
    }

    @Test void parsesRoomsIncludingEmptyListAndDefendsCollections() {
        ClientGameState state = lobby();
        receive(state, "ROOMS|r1:Networks:2:LOBBY;r2:Algorithms:5:RUNNING;r3:Java:0:FINISHED");
        var rooms = state.snapshot().availableRooms();
        assertEquals(3, rooms.size()); assertEquals(2, rooms.get(0).playerCount());
        assertThrows(UnsupportedOperationException.class, rooms::clear);
        receive(state, "ROOMS|"); assertTrue(state.snapshot().availableRooms().isEmpty()); assertEquals(3, rooms.size());
    }

    @Test void rosterContainsOnlyConfirmedSelfAndEvents() {
        ClientGameState state = room();
        assertEquals(java.util.Set.of("alice"), state.snapshot().knownPlayers());
        receive(state, "PLAYER_JOINED|bob"); receive(state, "PLAYER_JOINED|bob");
        assertEquals(2, state.snapshot().knownPlayers().size());
        receive(state, "PLAYER_LEFT|bob"); assertEquals(java.util.Set.of("alice"), state.snapshot().knownPlayers());
        assertTrue(state.snapshot().host()); assertEquals(GameStatus.WAITING_ROOM, state.snapshot().status());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().knownPlayers().add("mallory"));
    }

    @Test void questionAndSelectionRemainImmutableAndNoAnswerIsRevealedEarly() {
        ClientGameState state = question(); var q = state.snapshot().question();
        assertEquals("q1", q.id()); assertEquals(1, q.number()); assertEquals(2, q.total());
        assertEquals(List.of("UDP", "TCP", "ICMP", "ARP"), q.options());
        assertEquals(15, state.snapshot().remainingSeconds()); assertNull(state.snapshot().roundResult());
        assertThrows(UnsupportedOperationException.class, () -> q.options().set(0, "Changed"));
        state.answerSubmitted("q1", 1);
        assertEquals(GameStatus.ANSWER_SUBMITTED, state.snapshot().status());
        assertEquals(1, state.snapshot().selectedAnswer()); assertTrue(state.snapshot().answerSubmitted());
        assertThrows(IllegalStateException.class, () -> state.answerSubmitted("q1", 0));
        assertNull(state.snapshot().roundResult()); assertEquals(0, state.snapshot().totalScore());
    }

    @Test void nextQuestionResetsAnswerAndRoundDataButKeepsServerScore() {
        ClientGameState state = question(); state.answerSubmitted("q1", 1); receive(state, "RESULT|q1|1|130|130");
        assertEquals(GameStatus.ROUND_RESULT, state.snapshot().status());
        assertEquals(130, state.snapshot().roundResult().pointsAwarded());
        receive(state, MockQuizDemo.question(2).stripTrailing());
        assertEquals(GameStatus.QUESTION_ACTIVE, state.snapshot().status());
        assertNull(state.snapshot().selectedAnswer()); assertNull(state.snapshot().roundResult());
        assertFalse(state.snapshot().answerSubmitted()); assertEquals(130, state.snapshot().totalScore());
        assertThrows(IllegalStateException.class, () -> state.answerSubmitted("q1", 0));
    }

    @Test void incorrectAndUnansweredResultsUseServerValues() {
        ClientGameState state = question(); state.answerSubmitted("q1", 0); receive(state, "RESULT|q1|1|0|9");
        assertEquals(0, state.snapshot().selectedAnswer()); assertEquals(1, state.snapshot().roundResult().correctIndex());
        assertEquals(9, state.snapshot().totalScore()); // No local score calculation.
        receive(state, MockQuizDemo.question(2).stripTrailing()); receive(state, "RESULT|q2|0|0|9");
        assertNull(state.snapshot().selectedAnswer()); assertEquals(9, state.snapshot().totalScore());
    }

    @Test void finalStandingsAreAuthoritativeAndPreserveServerTieOrder() {
        ClientGameState state = question();
        receive(state, "GAME_OVER|bob|bob:200;alice:200");
        assertEquals(GameStatus.GAME_OVER, state.snapshot().status()); assertEquals("bob", state.snapshot().finalResult().winner());
        assertEquals("bob", state.snapshot().finalResult().standings().get(0).username());
        assertEquals(200, state.snapshot().totalScore());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().finalResult().standings().clear());
        state.leaderboard("r1", Map.of("alice", 999)); state.countdown("r1", 3);
        assertEquals(200, state.snapshot().totalScore()); assertEquals(200, state.snapshot().leaderboard().get(0).score());
    }

    @Test void hostPromotionInSameRoomDoesNotDestroyGameData() {
        ClientGameState state = question(); state.answerSubmitted("q1", 1);
        receive(state, "JOINED|r1|Networks|true");
        assertEquals(GameStatus.ANSWER_SUBMITTED, state.snapshot().status());
        assertTrue(state.snapshot().answerSubmitted()); assertEquals("q1", state.snapshot().question().id());
    }

    @Test void joiningAnotherRoomClearsPreviousGame() {
        ClientGameState state = question(); receive(state, "RESULT|q1|1|130|130");
        receive(state, "JOINED|r2|Java|false");
        assertEquals(GameStatus.WAITING_ROOM, state.snapshot().status()); assertFalse(state.snapshot().host());
        assertEquals(0, state.snapshot().totalScore()); assertNull(state.snapshot().question());
        assertNull(state.snapshot().finalResult()); assertTrue(state.snapshot().leaderboard().isEmpty());
    }

    @Test void udpFiltersWrongRoomInactiveRoundsAndInvalidData() {
        ClientGameState state = room(); state.countdown("r1", 10); state.leaderboard("r1", Map.of("alice", 500));
        assertNull(state.snapshot().remainingSeconds()); assertTrue(state.snapshot().leaderboard().isEmpty());
        receive(state, MockQuizDemo.question(1).stripTrailing());
        state.countdown("r2", 5); state.countdown("r1", -1); state.countdown("r1", 999);
        assertEquals(15, state.snapshot().remainingSeconds());
        state.countdown("r1", 8); state.countdown("r1", 9);
        assertEquals(8, state.snapshot().remainingSeconds());
        Map<String, Integer> scores = new HashMap<>(Map.of("alice", 10, "bob", 20));
        state.leaderboard("r1", scores); scores.put("alice", 9000);
        assertEquals("bob", state.snapshot().leaderboard().get(0).username());
        state.leaderboard("r2", Map.of("alice", 100)); state.leaderboard("r1", Map.of("bad:name", 10));
        state.leaderboard("r1", Map.of("alice", -1));
        assertEquals(20, state.snapshot().leaderboard().get(0).score());
        receive(state, "RESULT|q1|1|0|0"); state.countdown("r1", 5); assertEquals(0, state.snapshot().remainingSeconds());
    }

    @Test void informationalZeroCountdownDoesNotRejectAnAnswer() {
        ClientGameState state = question(); state.countdown("r1", 0);
        state.answerSubmitted("q1", 2); assertEquals(GameStatus.ANSWER_SUBMITTED, state.snapshot().status());
    }

    @Test void missingFeedDoesNotPreventCompleteGame() {
        ClientGameState state = question(); receive(state, "RESULT|q1|1|100|100");
        receive(state, MockQuizDemo.question(2).stripTrailing()); receive(state, "RESULT|q2|0|100|200");
        receive(state, "GAME_OVER|alice|alice:200"); assertEquals(GameStatus.GAME_OVER, state.snapshot().status());
    }

    @ParameterizedTest @ValueSource(strings = {"ALREADY_ANSWERED", "TOO_LATE"})
    void serverAnswerErrorsKeepInputLockedWithoutDroppingConnection(String code) {
        ClientGameState state = question(); state.answerSubmitted("q1", 1);
        receive(state, "ERROR|" + code + "|Server explanation");
        assertEquals(GameStatus.ANSWER_SUBMITTED, state.snapshot().status());
        assertTrue(state.snapshot().authenticated()); assertNull(state.snapshot().pendingAction());
        assertThrows(IllegalStateException.class, () -> state.answerSubmitted("q1", 2));
    }

    @Test void malformedAnswerRejectionAllowsRetryAndLateErrorDoesNotResetNewQuestion() {
        ClientGameState state = question(); state.answerSubmitted("q1", 1);
        receive(state, "ERROR|BAD_MESSAGE|invalid format");
        assertEquals(GameStatus.QUESTION_ACTIVE, state.snapshot().status()); assertFalse(state.snapshot().answerSubmitted());
        state.answerSubmitted("q1", 2); receive(state, MockQuizDemo.question(2).stripTrailing());
        receive(state, "ERROR|ALREADY_ANSWERED|old error");
        assertEquals(GameStatus.QUESTION_ACTIVE, state.snapshot().status()); assertNull(state.snapshot().selectedAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOMS|r1:Networks:-1:LOBBY", "ROOMS|r1:Networks:2:UNKNOWN", "ROOMS|r1:Networks:2:LOBBY;",
            "ROOMS|r1:Networks:2:LOBBY;r1:Networks:2:LOBBY", "ROOMS|bad", "JOINED|r1|Networks|yes",
            "QUESTION|q2|3|2|Text|A|B|C|D|15", "QUESTION|q2|1|2|Text|A|B|C|D|0",
            "RESULT|q1|4|100|100", "RESULT|q1|1|-1|100", "RESULT|q1|1|100|99999999999",
            "GAME_OVER|bob|alice:100", "GAME_OVER|alice|alice:100;alice:90", "GAME_OVER|alice|alice:bad",
            "PLAYER_JOINED|bad:name", "WELCOME|bob", "PONG|extra"})
    void malformedServerDataPreservesValidGame(String line) {
        ClientGameState state = question(); var original = state.snapshot().question();
        assertFalse(state.receive(MessageCodec.decode(line)));
        assertEquals(original, state.snapshot().question()); assertEquals(GameStatus.QUESTION_ACTIVE, state.snapshot().status());
        assertEquals("BAD_SERVER_MESSAGE", state.snapshot().errorCode());
    }

    @Test void ordinaryErrorsClearPendingRequestAndUseSafeNotifications() {
        ClientGameState state = lobby(); state.pending("JOIN");
        receive(state, "ERROR|ROOM_FULL|password=private token=private");
        assertNull(state.snapshot().pendingAction()); assertTrue(state.snapshot().authenticated());
        assertEquals(GameStatus.LOBBY, state.snapshot().status()); assertFalse(state.snapshot().notice().contains("private"));
        receive(state, "PONG"); assertNotNull(state.snapshot().lastPong());
    }

    @Test void localLeaveDoesNotInventAcknowledgementAndIgnoresOldGameMessages() {
        ClientGameState state = question(); state.leaveLocally();
        assertEquals(GameStatus.LOBBY, state.snapshot().status()); assertNull(state.snapshot().roomId());
        assertTrue(state.snapshot().notice().contains("no server acknowledgement"));
        receive(state, "RESULT|q1|1|100|100"); receive(state, MockQuizDemo.question(2).stripTrailing());
        assertNull(state.snapshot().question());
    }

    @Test void disconnectClearsStaleSessionData() {
        ClientGameState state = question(); state.answerSubmitted("q1", 1); state.leaderboard("r1", Map.of("alice", 100));
        state.disconnected("Server unavailable.");
        var s = state.snapshot(); assertEquals(GameStatus.DISCONNECTED, s.status()); assertFalse(s.connected());
        assertFalse(s.authenticated()); assertNull(s.username()); assertNull(s.serverHost()); assertEquals(0, s.serverPort());
        assertNull(s.roomId()); assertNull(s.question()); assertNull(s.selectedAnswer()); assertNull(s.finalResult());
        assertTrue(s.knownPlayers().isEmpty()); assertTrue(s.leaderboard().isEmpty()); assertEquals(0, s.totalScore());
    }

    @Test void snapshotsAndObserversRemainConsistentDuringConcurrentUpdates() throws Exception {
        ClientGameState state = question(); List<Long> versions = new ArrayList<>();
        state.addObserver(s -> { versions.add(s.version()); assertNotNull(s.question()); });
        state.addObserver(s -> { throw new IllegalStateException("Observer failure"); });
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                futures.add(pool.submit(() -> state.leaderboard("r1", Map.of("alice", index))));
            }
            for (var future : futures) { future.get(2, TimeUnit.SECONDS); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
        assertEquals(40, versions.size());
        for (int i = 1; i < versions.size(); i++) { assertTrue(versions.get(i) > versions.get(i - 1)); }
    }
}
