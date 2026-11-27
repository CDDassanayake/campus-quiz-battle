package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.RoomInfo;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.AsyncViewScope;
import com.quizbattle.client.ui.FormValidation;
import com.quizbattle.client.ui.RoomPresentation;
import com.quizbattle.client.ui.RoomReply;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.common.MessageCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GuiWorkflowTest {
    @ParameterizedTest @ValueSource(strings = {"localhost", "campus-server", "quiz.campus.test", "127.0.0.1", "::1", "[::1]"})
    void acceptsHostSyntaxWithoutDns(String value) { assertEquals(value, FormValidation.host(value)); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "my host", "https://server", "host/path", "host\\path", "host|token", "a\nb", "user@host"})
    void rejectsInvalidManualHosts(String value) { assertThrows(IllegalArgumentException.class, () -> FormValidation.host(value)); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"0", "65536", "-1", "abc", "1.2", "2147483648"})
    void validatesPortRange(String value) { assertThrows(IllegalArgumentException.class, () -> FormValidation.port(value)); }

    @Test void preservesManualPortsAndUsesNoConnectionAsSelection() {
        try (UiContext context = new UiContext(null, "FOUNDATION")) {
            var server = FormValidation.server(" campus.test ", "1", "65535"); context.selectServer(server);
            assertSame(server, context.selectedServer().orElseThrow()); assertEquals(1, server.gatewayPort()); assertEquals(65535, server.tlsPort());
            assertFalse(context.state().snapshot().connected()); assertFalse(context.state().snapshot().authenticated());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"a", "ab", "name with space", "bad|name", "12345678901234567"})
    void validatesLoginUsername(String value) { assertThrows(IllegalArgumentException.class, () -> FormValidation.username(value)); }

    @Test void registrationUsesOnlyContractPasswordRulesAndExactConfirmation() {
        FormValidation.registration("alice_1", new char[] {'x'}, new char[] {'x'});
        assertThrows(IllegalArgumentException.class, () -> FormValidation.password(new char[0]));
        assertThrows(IllegalArgumentException.class, () -> FormValidation.registration("alice", new char[] {'x'}, new char[] {'y'}));
        assertThrows(IllegalArgumentException.class, () -> FormValidation.registration("alice", new char[] {'x'}, null));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"bad|room", "bad:name", "abcdefghijklmnopqrstuvwxyz"})
    void validatesRoomName(String value) { assertThrows(IllegalArgumentException.class, () -> FormValidation.roomName(value)); }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "", "abc", "2147483648"})
    void validatesQuestionCountWithoutInventingServerMaximum(String value) { assertThrows(IllegalArgumentException.class, () -> FormValidation.questionCount(value)); }

    @Test void supportsFullPositiveIntegerQuestionRangeAndValidRoomName() {
        assertEquals(Integer.MAX_VALUE, FormValidation.questionCount("2147483647"));
        assertEquals("Campus Champions_1", FormValidation.roomName("Campus Champions_1"));
    }

    @Test void roomSearchAndStateFilterUseOnlyTheReceivedRooms() {
        var rooms = List.of(new RoomInfo("r1", "Networks", 2, RoomInfo.Status.LOBBY),
                new RoomInfo("r2", "Algorithms", 4, RoomInfo.Status.RUNNING), new RoomInfo("r3", "Networks Final", 1, RoomInfo.Status.FINISHED));
        assertEquals(2, RoomPresentation.filter(rooms, "NETWORK", "All rooms").size());
        assertEquals(List.of(rooms.get(1)), RoomPresentation.filter(rooms, "r2", "RUNNING"));
        assertTrue(RoomPresentation.filter(rooms, "missing", "All rooms").isEmpty());
        assertTrue(RoomPresentation.filter(List.of(), "", "All rooms").isEmpty());
        assertTrue(RoomPresentation.joinable(rooms.get(0))); assertFalse(RoomPresentation.joinable(rooms.get(1))); assertFalse(RoomPresentation.joinable(rooms.get(2)));
    }

    private static ClientGameState lobby() {
        ClientGameState state = new ClientGameState(); state.receive(MessageCodec.decode("WELCOME|alice")); return state;
    }
    @Test void roomCreationWaitsForJoinedInsteadOfSocketWrite() {
        ClientGameState state = lobby(); RoomReply reply = new RoomReply(RoomReply.Kind.JOINED, state.snapshot().version());
        state.pending("CREATE_ROOM"); reply.observe(state.snapshot()); assertFalse(reply.future().isDone());
        state.receive(MessageCodec.decode("JOINED|r1|Networks|true")); reply.observe(state.snapshot());
        assertTrue(reply.future().isDone()); assertFalse(reply.future().isCompletedExceptionally());
    }
    @Test void hostStartWaitsForAnActualQuestionAndRetainsItsData() {
        ClientGameState state = lobby(); state.receive(MessageCodec.decode("JOINED|r1|Networks|true"));
        RoomReply reply = new RoomReply(RoomReply.Kind.QUESTION, state.snapshot().version());
        state.pending("START"); reply.observe(state.snapshot()); assertFalse(reply.future().isDone());
        state.receive(MessageCodec.decode(MockQuizDemo.question(1).stripTrailing())); reply.observe(state.snapshot());
        assertTrue(reply.future().isDone()); assertEquals("TCP", state.snapshot().question().options().get(1));
    }

    @ParameterizedTest @ValueSource(strings = {"ROOM_FULL", "ROOM_NOT_FOUND", "GAME_RUNNING", "BAD_MESSAGE", "NOT_HOST"})
    void serverErrorsReleasePresentationWaitsWithoutDroppingConnection(String code) {
        ClientGameState state = lobby(); RoomReply reply = new RoomReply(RoomReply.Kind.JOINED, state.snapshot().version());
        state.receive(MessageCodec.decode("ERROR|" + code + "|private upstream text")); reply.observe(state.snapshot());
        assertTrue(reply.future().isCompletedExceptionally()); assertTrue(state.snapshot().authenticated());
    }

    @Test void disconnectEndsPendingRoomWait() {
        ClientGameState state = lobby(); RoomReply reply = new RoomReply(RoomReply.Kind.JOINED, state.snapshot().version());
        state.disconnected("Disconnected."); reply.observe(state.snapshot()); assertTrue(reply.future().isCompletedExceptionally());
    }

    @Test void asyncScopePreventsDuplicatesAndDispatchesCompletions() {
        List<Runnable> ui = new ArrayList<>(); List<Boolean> busy = new ArrayList<>(); List<String> received = new ArrayList<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        try (AsyncViewScope scope = new AsyncViewScope(ui::add, busy::add)) {
            assertTrue(scope.run(() -> pending, Duration.ofSeconds(2), received::add, failure -> fail(failure)));
            assertFalse(scope.run(() -> CompletableFuture.completedFuture("duplicate"), Duration.ofSeconds(2), received::add, failure -> fail(failure)));
            pending.complete("done"); assertTrue(received.isEmpty()); ui.remove(0).run();
            assertEquals(List.of("done"), received); assertEquals(List.of(true, false), busy); assertFalse(scope.busy());
        }
    }

    @Test void disposedScopeIgnoresLateSuccessAndLateErrors() {
        List<Runnable> ui = new ArrayList<>(); CompletableFuture<String> result = new CompletableFuture<>();
        AsyncViewScope scope = new AsyncViewScope(ui::add, busy -> { });
        scope.run(() -> result, Duration.ofSeconds(2), value -> fail("Stale success"), failure -> fail("Stale error"));
        scope.close(); result.complete("old screen"); ui.forEach(Runnable::run);
        assertFalse(scope.run(() -> result, Duration.ofSeconds(2), value -> fail(), failure -> fail()));
    }

    @Test void scopeTimeoutDoesNotCancelOrRewriteSharedServiceFuture() throws Exception {
        var ui = new LinkedBlockingQueue<Runnable>(); var result = new CompletableFuture<String>(); var failed = new CompletableFuture<Throwable>();
        try (AsyncViewScope scope = new AsyncViewScope(ui::add, busy -> { })) {
            scope.run(() -> result, Duration.ofMillis(25), value -> fail(), failed::complete);
            Runnable callback = ui.poll(2, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failed.get()); assertFalse(result.isDone());
            result.complete("Late shared result"); assertTrue(ui.isEmpty());
        }
    }

    @Test void serviceSuppliersAndCleanupRunOffTheCallerThread() throws Exception {
        GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(), null);
        UiContext context = new UiContext(service, "MOCK");
        try {
            String thread = context.invoke(() -> CompletableFuture.completedFuture(Thread.currentThread().getName())).get(2, TimeUnit.SECONDS);
            assertEquals("quizbattle-ui-actions", thread);
        } finally { context.close(); assertTrue(context.awaitTermination(Duration.ofSeconds(2))); }
    }
}
