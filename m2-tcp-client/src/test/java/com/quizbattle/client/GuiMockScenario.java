package com.quizbattle.client;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.MessageType;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit GUI test fixture: no production room engine, credentials or scoring. */
final class GuiMockScenario implements AutoCloseable {
    private final FakeSecureLoginClient auth;
    private final boolean autoStartGuest;
    private final ScheduledExecutorService scriptedEvents = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mock-room-events"); thread.setDaemon(true); return thread;
    });
    private volatile Socket active;
    private volatile String room;
    private volatile boolean host;
    final java.util.concurrent.atomic.AtomicInteger roomRequests = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger creates = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger hellos = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger answers = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.BlockingQueue<Integer> receivedAnswers = new java.util.concurrent.LinkedBlockingQueue<>();
    volatile java.util.concurrent.CountDownLatch welcomeGate;
    volatile java.util.concurrent.CountDownLatch joinedGate;
    volatile java.util.concurrent.CountDownLatch questionGate;
    volatile String nextJoinError;
    volatile String nextAnswerError;
    volatile boolean fullGame;
    volatile boolean automatic = true;
    volatile boolean missingFeed;
    volatile FakeLiveFeedReceiver feed;
    volatile java.util.concurrent.CountDownLatch resultGate;
    private int round;
    private int totalScore;
    private Integer choice;
    private boolean finished;
    private long generation;
    GuiMockScenario(FakeSecureLoginClient auth, boolean autoStartGuest) { this.auth = auth; this.autoStartGuest = autoStartGuest; }
    void play(Socket socket, BufferedReader input) throws Exception {
        socket.setSoTimeout(0); active = socket; room = null;
        if (!("HELLO|" + FakeSecureLoginClient.TOKEN).equals(input.readLine())) { throw new IOException("Mock expected HELLO."); }
        hellos.incrementAndGet(); waitFor(welcomeGate);
        send(socket, new Message(MessageType.WELCOME, List.of(auth.username == null ? "alice" : auth.username)));
        String line;
        try {
        while ((line = input.readLine()) != null) {
            Message message = MessageCodec.decode(line);
            switch (message.type()) {
                case LIST_ROOMS -> { roomRequests.incrementAndGet(); send(socket, new Message(MessageType.ROOMS,
                        List.of("r1:Networks:2:LOBBY;r2:Campus Rivals:3:LOBBY;r3:Algorithms:4:RUNNING;r4:Java League:2:FINISHED"))); }
                case CREATE_ROOM -> {
                    creates.incrementAndGet();
                    if (message.getField(0).equals("Taken")) { error(socket, "BAD_MESSAGE", "Scripted creation rejection"); break; }
                    waitFor(joinedGate);
                    room = "r5"; host = true;
                    send(socket, new Message(MessageType.JOINED, List.of(room, message.getField(0), "true"))); players(socket);
                }
                case JOIN -> {
                    if (nextJoinError != null) { String code = nextJoinError; nextJoinError = null; error(socket, code, "Scripted join rejection"); break; }
                    if (!List.of("r1", "r2").contains(message.getField(0))) { error(socket, "ROOM_NOT_FOUND", "Mock room missing"); break; }
                    room = message.getField(0); host = false;
                    send(socket, new Message(MessageType.JOINED, List.of(room, room.equals("r1") ? "Networks" : "Campus Rivals", "false"))); players(socket);
                    if (autoStartGuest) {
                        String joined = room;
                        scriptedEvents.schedule(() -> {
                            try { if (active == socket && joined.equals(room)) { firstQuestion(); } } catch (IOException ignored) { }
                        }, 3, TimeUnit.SECONDS);
                    }
                }
                case START -> { starts.incrementAndGet(); if (!host) { error(socket, "NOT_HOST", "Mock host only"); } else { waitFor(questionGate); firstQuestion(); } }
                case LEAVE -> { room = null; generation++; } // No invented acknowledgement.
                case PING -> send(socket, new Message(MessageType.PONG, List.of()));
                case ANSWER -> {
                    answers.incrementAndGet();
                    if (!fullGame) { error(socket, "BAD_MESSAGE", "Gameplay is not enabled for this fixture"); break; }
                    if (nextAnswerError != null) { String code = nextAnswerError; nextAnswerError = null; error(socket, code, "Scripted answer rejection"); break; }
                    if (room == null) { error(socket, "NOT_IN_ROOM", "No mock room"); break; }
                    if (finished || choice != null) { error(socket, "ALREADY_ANSWERED", "Already submitted"); break; }
                    if (!message.getField(0).equals("q" + round)) { error(socket, "TOO_LATE", "Old mock round"); break; }
                    synchronized (this) { choice = Integer.valueOf(message.getField(1)); receivedAnswers.add(choice); }
                    waitFor(resultGate);
                    if (automatic) { finishRound(); }
                }
                default -> { if (message.type() == MessageType.ANSWER) { answers.incrementAndGet(); } error(socket, "BAD_MESSAGE", "Not part of this GUI milestone"); }
            }
        }
        } catch (java.net.SocketException e) {
            if (!socket.isClosed()) { throw e; } // Explicit scripted shutdown; accept the next test session.
        } finally {
            if (active == socket) { room = null; active = null; }
        }
    }
    void firstQuestion() throws IOException {
        if (fullGame) { totalScore = 0; round = 0; nextQuestion(); return; }
        Socket socket = active;
        if (socket != null && room != null) {
            send(socket, new Message(MessageType.QUESTION, List.of("q1", "1", "2", "Which transport provides reliable ordered delivery?", "UDP", "TCP", "ICMP", "ARP", "15")));
        }
    }
    synchronized void nextQuestion() throws IOException {
        if (active == null || room == null) { return; }
        if (round >= 3) {
            send(active, new Message(MessageType.GAME_OVER, List.of("bob", "bob:250;" + username() + ":" + totalScore + ";charlie:75;diana:50")));
            generation++; return;
        }
        round++; choice = null; finished = false; long epoch = ++generation;
        String[][] questions = {
                {"Which transport provides reliable ordered delivery?", "UDP", "TCP", "ICMP", "ARP"},
                {"Which statement describes the role of a TCP client in this distributed quiz application?", "It calculates every player's score locally", "It replaces the shared protocol when a packet is lost", "It sends answers and presents authoritative results received from the server", "It starts the next round whenever the local countdown reaches zero"},
                {"Which protocol provides a connectionless datagram service?", "UDP", "TCP", "TLS", "HTTP"}
        };
        String[] q = questions[round - 1];
        send(active, new Message(MessageType.QUESTION, List.of("q" + round, "" + round, "3", q[0], q[1], q[2], q[3], q[4], "15")));
        if (automatic) {
            for (int seconds = 14; seconds >= 0; seconds--) {
                int value = seconds;
                scriptedEvents.schedule(() -> {
                    synchronized (this) {
                        if (generation != epoch || room == null || finished) { return; }
                        if (!missingFeed && feed != null) { feed.countdown(room, value); }
                        if (value == 0) { try { finishRound(); } catch (IOException ignored) { } }
                    }
                }, 15 - seconds, TimeUnit.SECONDS);
            }
        }
        emitRankings();
    }
    synchronized void finishRound() throws IOException {
        if (active == null || room == null || finished) { return; }
        finished = true; int correct = new int[]{1, 2, 0}[round - 1];
        // Explicit mock fixture values, never a production scoring implementation.
        int awarded = choice == null ? 0 : choice == correct ? 100 : 7;
        totalScore += awarded;
        send(active, new Message(MessageType.RESULT, List.of("q" + round, "" + correct, "" + awarded, "" + totalScore)));
        emitRankings(); long epoch = generation;
        if (automatic) { scriptedEvents.schedule(() -> {
            synchronized (this) { if (generation == epoch && room != null) { try { nextQuestion(); } catch (IOException ignored) { } } }
        }, 3, TimeUnit.SECONDS); }
    }
    void emitRankings() {
        if (!missingFeed && feed != null && room != null) { feed.leaderboard(room, java.util.Map.of(username(), totalScore, "bob", 250, "charlie", 75)); }
    }
    void disconnectServer() throws IOException { Socket socket = active; if (socket != null) { socket.close(); } }
    void finalFixture(String winner, String standings) throws IOException { send(active, new Message(MessageType.GAME_OVER, List.of(winner, standings))); }
    void rapidTransition() throws IOException {
        Socket socket = active;
        synchronized (socket) {
            FakeGameServer.write(socket, "RESULT|q2|2|16|123\nQUESTION|rapid-id|3|3|A rapid server-controlled replacement|Alpha|Beta|Gamma|Delta|15\n");
        }
    }
    void longQuestion() throws IOException {
        firstQuestion();
        send(active, new Message(MessageType.QUESTION, List.of("q1", "1", "3",
                "A reliable client presents the server's question exactly as received. Which transport delivers these ordered messages while the graphical interface remains responsive?",
                "UDP datagrams may be lost or reordered, so this answer deliberately contains a longer explanation for the layout review.",
                "TCP provides the reliable, ordered stream used for quiz questions, answers and authoritative results.",
                "The user interface must calculate scores independently and replace the server's results whenever packets are delayed.",
                "A local timer should invent the next question when its displayed countdown reaches zero, regardless of the server.", "15")));
    }
    private String username() { return auth.username == null ? "alice" : auth.username; }
    private static void players(Socket socket) throws IOException {
        send(socket, new Message(MessageType.PLAYER_JOINED, List.of("bob")));
        send(socket, new Message(MessageType.PLAYER_JOINED, List.of("charlie")));
        send(socket, new Message(MessageType.PLAYER_LEFT, List.of("charlie")));
    }
    private static void error(Socket socket, String code, String text) throws IOException { send(socket, new Message(MessageType.ERROR, List.of(code, text))); }
    private static void send(Socket socket, Message message) throws IOException {
        synchronized (socket) { FakeGameServer.write(socket, MessageCodec.encode(message) + "\n"); }
    }
    private static void waitFor(java.util.concurrent.CountDownLatch gate) throws IOException {
        if (gate == null) { return; }
        try { if (!gate.await(8, TimeUnit.SECONDS)) { throw new IOException("Mock server event deadline expired"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Mock server event cancelled"); }
    }
    @Override public void close() { scriptedEvents.shutdownNow(); }
}
