package com.quizbattle.client.service;

import com.quizbattle.client.ClientMessageListener;
import com.quizbattle.client.ClientTimeouts;
import com.quizbattle.client.TcpGameClient;
import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.common.LiveFeedListener;
import com.quizbattle.common.LiveFeedReceiver;
import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;
import com.quizbattle.common.SecureLoginClient;
import com.quizbattle.common.ServerFinder;
import com.quizbattle.common.ServerInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * UI-independent asynchronous facade. One event executor serializes TCP events,
 * UDP events and commands. Slow discovery, TLS and TCP connect use I/O workers.
 * Futures complete after local work (only connect/login wait for WELCOME), not
 * after server acceptance of a game command. Observers must never join futures.
 */
public final class GameClientService implements AutoCloseable, LiveFeedListener {
    private final ClientGameState state = new ClientGameState();
    private final ServerFinder finder;
    private final SecureLoginClient loginClient;
    private final Supplier<? extends LiveFeedReceiver> receiverFactory;
    private final ClientTimeouts timeouts;
    private final ExecutorService events = executor("quizbattle-client-events", 1);
    private final ExecutorService io = executor("quizbattle-client-io", 2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile Session current;
    private CompletableFuture<List<com.quizbattle.client.model.RoomInfo>> roomListReply; // Event executor only.
    private final Duration roomReplyTimeout;
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "quizbattle-client-deadlines"); thread.setDaemon(true); return thread;
    });
    private java.util.concurrent.ScheduledFuture<?> roomListExpiry; // Event executor only.
    private long registrationGeneration; // Event executor only; invalidates obsolete UI notifications.

    private static ExecutorService executor(String name, int count) {
        return Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
        });
    }

    private static final class Session {
        final CompletableFuture<Void> ready;
        volatile TcpGameClient tcp;
        volatile LiveFeedReceiver receiver;
        volatile boolean cancelled;
        Session(CompletableFuture<Void> ready) { this.ready = ready; }
        void cancel() {
            cancelled = true;
            TcpGameClient client = tcp; if (client != null) { client.close(); }
            LiveFeedReceiver feed = receiver;
            if (feed != null) { try { feed.close(); } catch (RuntimeException ignored) { } }
        }
    }

    public GameClientService(ServerFinder finder, SecureLoginClient loginClient,
                             Supplier<? extends LiveFeedReceiver> receiverFactory) {
        this(finder, loginClient, receiverFactory, ClientTimeouts.defaults());
    }

    public GameClientService(ServerFinder finder, SecureLoginClient loginClient,
                             Supplier<? extends LiveFeedReceiver> receiverFactory, ClientTimeouts timeouts) {
        this(finder, loginClient, receiverFactory, timeouts, Duration.ofSeconds(8));
    }

    public GameClientService(ServerFinder finder, SecureLoginClient loginClient,
                             Supplier<? extends LiveFeedReceiver> receiverFactory, ClientTimeouts timeouts, Duration roomReplyTimeout) {
        this.finder = Objects.requireNonNull(finder);
        this.loginClient = Objects.requireNonNull(loginClient);
        this.receiverFactory = receiverFactory; // null explicitly means TCP-only gameplay.
        this.timeouts = Objects.requireNonNull(timeouts);
        if (roomReplyTimeout == null || roomReplyTimeout.isNegative() || roomReplyTimeout.isZero()) {
            throw new IllegalArgumentException("Room reply timeout must be positive.");
        }
        this.roomReplyTimeout = roomReplyTimeout;
    }

    public ClientGameState state() { return state; }

    private <T> CompletableFuture<T> future() {
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result); result.whenComplete((value, error) -> pending.remove(result));
        if (closed.get()) { result.completeExceptionally(new IOException("Client service is closed.")); }
        return result;
    }

    private void enqueue(Runnable action) {
        if (!closed.get()) {
            try { events.execute(() -> { if (!closed.get()) { action.run(); } }); }
            catch (RejectedExecutionException ignored) { /* close() already completes pending futures. */ }
        }
    }

    private void background(Runnable task, CompletableFuture<?> result) {
        try { io.execute(() -> { if (!result.isDone()) { task.run(); } }); }
        catch (RejectedExecutionException e) { result.completeExceptionally(new IOException("Client service is closed.")); }
    }

    private void fail(CompletableFuture<?> result, String description) {
        state.operationFailed(description); result.completeExceptionally(new IOException(description));
    }

    public CompletableFuture<List<ServerInfo>> discoverServers(Duration timeout) {
        CompletableFuture<List<ServerInfo>> result = future();
        if (result.isDone()) { return result; }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            result.completeExceptionally(new IOException("Discovery timeout must be positive.")); return result;
        }
        background(() -> {
            try {
                List<ServerInfo> servers = List.copyOf(finder.find(timeout));
                enqueue(() -> result.complete(servers));
            } catch (Exception e) {
                enqueue(() -> result.completeExceptionally(new IOException("Server discovery failed. Enter a server address manually.")));
            }
        }, result);
        return result;
    }

    /** Takes ownership of password: clears the caller's array immediately and its copy on completion. */
    public CompletableFuture<Void> register(ServerInfo server, String username, char[] password) {
        CompletableFuture<Void> result = future();
        char[] temporary = takePassword(password);
        result.whenComplete((value, failure) -> Arrays.fill(temporary, '\0'));
        if (result.isDone()) { return result; }
        if (!validCredentials(server, username, temporary)) {
            result.completeExceptionally(new IOException("Use a valid server, a 3-16 character username and a nonempty password."));
            return result;
        }
        enqueue(() -> {
            long request = ++registrationGeneration;
            background(() -> {
                try {
                    loginClient.register(server.host(), server.tlsPort(), username, temporary);
                    Arrays.fill(temporary, '\0');
                    enqueue(() -> {
                        if (!result.isDone() && request == registrationGeneration && current == null) {
                            state.notification("Registration succeeded. You can now log in.");
                        }
                        result.complete(null);
                    });
                } catch (Exception e) {
                    Arrays.fill(temporary, '\0');
                    AuthenticationFailure failure = AuthenticationFailure.from(e, true);
                    enqueue(() -> {
                        if (!result.isDone() && request == registrationGeneration && current == null) {
                            state.operationFailed(failure.getMessage());
                        }
                        result.completeExceptionally(failure);
                    });
                } finally { Arrays.fill(temporary, '\0'); }
            }, result);
        });
        return result;
    }

    /** Logs in through the injected TLS interface, then connects with its token. */
    public CompletableFuture<Void> login(ServerInfo server, String username, char[] password) {
        CompletableFuture<Void> result = future();
        char[] temporary = takePassword(password);
        result.whenComplete((value, failure) -> Arrays.fill(temporary, '\0'));
        if (result.isDone()) { return result; }
        if (!validCredentials(server, username, temporary)) {
            result.completeExceptionally(new IOException("Use a valid server, a 3-16 character username and a nonempty password."));
            return result;
        }
        enqueue(() -> {
            Session session = begin(server, result);
            if (session == null) { return; }
            state.authenticating();
            background(() -> {
                String token;
                try { token = loginClient.login(server.host(), server.tlsPort(), username, temporary); }
                catch (Exception e) {
                    Arrays.fill(temporary, '\0');
                    AuthenticationFailure failure = AuthenticationFailure.from(e, false);
                    enqueue(() -> {
                        if (active(session)) { endSession(session, failure.getMessage(), failure); }
                    }); return;
                } finally { Arrays.fill(temporary, '\0'); }
                open(session, server, token);
            }, result);
        });
        return result;
    }

    public CompletableFuture<Void> connect(ServerInfo server, String token) {
        CompletableFuture<Void> result = future();
        enqueue(() -> {
            Session session = begin(server, result);
            if (session != null) { background(() -> open(session, server, token), result); }
        });
        return result;
    }

    private Session begin(ServerInfo server, CompletableFuture<Void> result) {
        if (server == null) { fail(result, "Select a valid server."); return null; }
        if (current != null) { fail(result, "Disconnect the current session before connecting again."); return null; }
        registrationGeneration++;
        Session session = new Session(result); current = session; state.connecting(server); return session;
    }

    private boolean active(Session session) { return current == session && !session.cancelled && !closed.get(); }

    private void open(Session session, ServerInfo server, String token) {
        if (!active(session)) { return; }
        try {
            TcpGameClient tcp = new TcpGameClient(server.host(), server.gatewayPort(), token, timeouts);
            session.tcp = tcp;
            if (!active(session)) { tcp.close(); return; }
            tcp.addListener(new ClientMessageListener() {
                @Override public void onAuthenticating() {
                    enqueue(() -> { if (active(session)) { state.transportConnected(); } });
                }
                @Override public void onMessage(Message message) {
                    enqueue(() -> {
                        if (!active(session)) { return; }
                        boolean valid = state.receive(message);
                        if (roomListReply != null && (message.type() == MessageType.ROOMS || message.type() == MessageType.ERROR)) {
                            var reply = roomListReply; roomListReply = null;
                            cancelRoomExpiry();
                            if (valid && message.type() == MessageType.ROOMS) { reply.complete(state.snapshot().availableRooms()); }
                            else { reply.completeExceptionally(new IOException("The server could not provide a valid room list. Try refreshing again.")); }
                        }
                        if (valid && message.type() == MessageType.WELCOME) {
                            startFeed(session); session.ready.complete(null);
                        }
                    });
                }
                @Override public void onDisconnected(IOException failure) {
                    enqueue(() -> {
                        if (active(session)) {
                            endSession(session, failure == null ? "Disconnected." : "Game server connection lost. Log in again to reconnect.");
                        }
                    });
                }
            });
            tcp.connect();
        } catch (Exception e) {
            String description = e instanceof IOException ? e.getMessage()
                    : "Could not connect. Check the server settings and authentication token.";
            enqueue(() -> connectionFailed(session, description));
        }
    }

    private void connectionFailed(Session session, String description) {
        if (active(session)) { endSession(session, description); }
    }

    private void endSession(Session session, String description) {
        endSession(session, description, new IOException(description));
    }
    private void endSession(Session session, String description, IOException failure) {
        registrationGeneration++;
        session.cancel(); if (current == session) { current = null; }
        state.disconnected(description);
        session.ready.completeExceptionally(failure);
        if (roomListReply != null) { roomListReply.completeExceptionally(failure); roomListReply = null; }
        cancelRoomExpiry();
    }

    private void cancelRoomExpiry() {
        if (roomListExpiry != null) { roomListExpiry.cancel(false); roomListExpiry = null; }
    }

    private void startFeed(Session session) {
        if (receiverFactory == null) { return; }
        try {
            LiveFeedReceiver receiver = Objects.requireNonNull(receiverFactory.get());
            session.receiver = receiver;
            receiver.start(new LiveFeedListener() {
                @Override public void onCountdown(String roomId, int secondsLeft) { countdown(session, roomId, secondsLeft); }
                @Override public void onLeaderboard(String roomId, Map<String, Integer> scores) { leaderboard(session, roomId, scores); }
            });
            if (!active(session)) { receiver.close(); }
        } catch (Exception e) {
            LiveFeedReceiver receiver = session.receiver;
            if (receiver != null) { try { receiver.close(); } catch (RuntimeException ignored) { } }
            session.receiver = null;
            state.notification("Live feed unavailable; TCP gameplay is available.");
        }
    }

    @FunctionalInterface private interface Command { void run(TcpGameClient tcp) throws IOException; }

    private CompletableFuture<Void> command(String name, Command command) {
        CompletableFuture<Void> result = future();
        enqueue(() -> {
            Session session = current;
            if (session == null || !active(session) || !state.snapshot().authenticated()) {
                fail(result, "Connect and authenticate before sending game commands."); return;
            }
            try { command.run(session.tcp); result.complete(null); }
            catch (IllegalArgumentException | IllegalStateException e) { fail(result, e.getMessage()); }
            catch (IOException e) { endSession(session, "Failed to send " + name + "; connection closed."); result.completeExceptionally(new IOException("Game connection failed.")); }
        });
        return result;
    }

    private void lobbyAction() {
        var snapshot = state.snapshot();
        if (snapshot.status() != GameStatus.LOBBY || snapshot.pendingAction() != null) {
            throw new IllegalStateException("Wait for the current request or return to the lobby first.");
        }
    }

    public CompletableFuture<Void> listRooms() { return command("LIST_ROOMS", TcpGameClient::listRooms); }
    /** Completes on the actual ROOMS reply; the existing send-only listRooms API is preserved. */
    public CompletableFuture<List<com.quizbattle.client.model.RoomInfo>> refreshRooms() {
        CompletableFuture<List<com.quizbattle.client.model.RoomInfo>> result = future();
        enqueue(() -> {
            if (roomListReply != null) {
                roomListReply.whenComplete((rooms, failure) -> {
                    if (failure == null) { result.complete(rooms); } else { result.completeExceptionally(failure); }
                });
                return;
            }
            Session session = current;
            if (session == null || !active(session) || !state.snapshot().authenticated()) {
                fail(result, "Connect and authenticate before refreshing rooms."); return;
            }
            roomListReply = result;
            try {
                session.tcp.listRooms();
                roomListExpiry = deadlines.schedule(() -> enqueue(() -> {
                    if (roomListReply == result) {
                        roomListReply = null; roomListExpiry = null;
                        result.completeExceptionally(new IOException("Room list request timed out. Refresh to retry, or disconnect if the server is unavailable."));
                    }
                }), roomReplyTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
            catch (RejectedExecutionException e) { roomListReply = null; result.completeExceptionally(new IOException("Client service is closed.")); }
            catch (IOException e) { endSession(session, "Failed to request rooms; connection closed."); }
        });
        return result;
    }
    public CompletableFuture<Void> createRoom(String name, int count) {
        return command("CREATE_ROOM", tcp -> { lobbyAction(); tcp.createRoom(name, count); state.pending("CREATE_ROOM"); });
    }
    public CompletableFuture<Void> joinRoom(String roomId) {
        return command("JOIN", tcp -> { lobbyAction(); tcp.join(roomId); state.pending("JOIN"); });
    }
    public CompletableFuture<Void> leaveRoom() {
        return command("LEAVE", tcp -> {
            if (state.snapshot().roomId() == null) { throw new IllegalStateException("You are not in a known room."); }
            tcp.leave(); state.leaveLocally(); tcp.listRooms();
        });
    }
    public CompletableFuture<Void> startGame() {
        return command("START", tcp -> {
            var s = state.snapshot();
            if (s.status() != GameStatus.WAITING_ROOM || !s.host() || s.pendingAction() != null) {
                throw new IllegalStateException("Only the waiting room host can start; wait for any pending request.");
            }
            tcp.start(); state.pending("START");
        });
    }
    public CompletableFuture<Void> submitAnswer(String questionId, int choiceIndex) {
        return command("ANSWER", tcp -> {
            var s = state.snapshot();
            if (s.status() != GameStatus.QUESTION_ACTIVE || s.question() == null
                    || !s.question().id().equals(questionId) || s.answerSubmitted()) {
                throw new IllegalStateException("Answer is outdated or already submitted. Use the displayed question ID.");
            }
            tcp.answer(questionId, choiceIndex); state.answerSubmitted(questionId, choiceIndex);
        });
    }
    /** Guards the displayed question instance, including a later QUESTION reusing the same wire ID. */
    public CompletableFuture<Void> submitAnswer(com.quizbattle.client.model.Question displayed, int choiceIndex) {
        return command("ANSWER", tcp -> {
            var s = state.snapshot();
            if (displayed == null || s.question() != displayed || s.status() != GameStatus.QUESTION_ACTIVE
                    || s.answerSubmitted()) {
                throw new IllegalStateException("The displayed question is no longer accepting local answers.");
            }
            tcp.answer(displayed.id(), choiceIndex); state.answerSubmitted(displayed.id(), choiceIndex);
        });
    }
    public CompletableFuture<Void> ping() { return command("PING", TcpGameClient::ping); }

    @Override public void onCountdown(String roomId, int secondsLeft) { countdown(current, roomId, secondsLeft); }
    private void countdown(Session session, String roomId, int secondsLeft) {
        enqueue(() -> { if (session != null && active(session)) { state.countdown(roomId, secondsLeft); } });
    }
    @Override public void onLeaderboard(String roomId, Map<String, Integer> scores) { leaderboard(current, roomId, scores); }
    private void leaderboard(Session session, String roomId, Map<String, Integer> scores) {
        Map<String, Integer> copy;
        try { copy = Map.copyOf(scores); } catch (RuntimeException e) { return; }
        enqueue(() -> { if (session != null && active(session)) { state.leaderboard(roomId, copy); } });
    }

    public CompletableFuture<Void> disconnect() {
        Session session = current; if (session != null) { session.cancel(); }
        CompletableFuture<Void> result = future();
        enqueue(() -> {
            registrationGeneration++;
            Session latest = current;
            if (latest != null) { endSession(latest, "Disconnected."); }
            else { state.disconnected("Disconnected."); }
            result.complete(null);
        });
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        Session session = current; if (session != null) { session.cancel(); }
        pending.forEach(f -> f.completeExceptionally(new IOException("Client service is closed.")));
        io.shutdownNow();
        deadlines.shutdownNow();
        events.execute(() -> { cancelRoomExpiry(); current = null; state.disconnected("Client service closed."); });
        events.shutdown();
    }

    /** For launchers/tests after close(); never call from an observer. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        return events.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                && io.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                && deadlines.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private static char[] takePassword(char[] password) {
        if (password == null) { return new char[0]; }
        char[] copy = password.clone(); Arrays.fill(password, '\0'); return copy;
    }
    private static boolean validCredentials(ServerInfo server, String username, char[] password) {
        return server != null && username != null && username.matches("[A-Za-z0-9_]{3,16}") && password.length > 0;
    }
}
