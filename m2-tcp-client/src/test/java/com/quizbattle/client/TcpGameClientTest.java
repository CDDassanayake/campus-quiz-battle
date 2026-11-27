package com.quizbattle.client;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.MessageType;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class TcpGameClientTest {
    // Deliberately synthetic protocol fixture, never a real credential.
    private static final String TEST_TOKEN = "synthetic-test-token";

    private static final class Recorder implements ClientMessageListener {
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<IOException> ended = new CompletableFuture<>();
        final AtomicInteger disconnects = new AtomicInteger();
        final List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Thread readerThread;

        @Override public void onMessage(Message message) {
            readerThread = Thread.currentThread();
            threads.add(Thread.currentThread().getName());
            messages.add(message);
        }
        @Override public void onDisconnected(IOException failure) {
            disconnects.incrementAndGet();
            ended.complete(failure);
        }
        Message next() throws Exception {
            Message result = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(result, "Expected a message callback");
            return result;
        }
        IOException failure() throws Exception { return ended.get(3, TimeUnit.SECONDS); }
    }

    private static TcpGameClient client(FakeGameServer server) {
        return new TcpGameClient("localhost", server.port(), TEST_TOKEN);
    }

    private static void welcome(java.net.Socket socket, java.io.BufferedReader input) throws Exception {
        assertEquals("HELLO|" + TEST_TOKEN, input.readLine());
        FakeGameServer.write(socket, "WELCOME|alice\n");
    }

    @Test void connectsResolvesAuthenticatesAndClosesCleanly() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            assertNull(in.readLine(), "Client close must release the socket");
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder();
            client.addListener(recorder);
            assertEquals(TcpGameClient.State.NEW, client.state());
            client.connect();
            assertEquals(MessageType.WELCOME, recorder.next().type());
            assertTrue(client.isConnected());
            assertTrue(client.isAuthenticated());
            assertEquals("alice", client.username().orElseThrow());
            assertEquals(InetAddress.getByName("localhost"), client.resolvedAddress().orElseThrow());
            assertThrows(IOException.class, client::connect);
            client.close();
            client.close();
            assertNull(recorder.failure());
            recorder.readerThread.join(2000);
            assertFalse(recorder.readerThread.isAlive(), "Client reader must terminate after close");
            assertEquals(1, recorder.disconnects.get());
            assertEquals(TcpGameClient.State.CLOSED, client.state());
            assertFalse(client.isAuthenticated());
            assertTrue(client.username().isEmpty());
            assertThrows(IOException.class, client::ping);
            assertThrows(IOException.class, client::connect);
            server.awaitScript();
        }
    }

    @Test void encodesEveryCommandAndKeepsTheConnectionOpen() throws Exception {
        List<String> expected = List.of("LIST_ROOMS", "CREATE_ROOM|Networks|5", "JOIN|r1", "LEAVE",
                "START", "ANSWER|q1|0", "ANSWER|q1|1", "ANSWER|q1|2", "ANSWER|q1|3", "PING");
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            for (String line : expected) { assertEquals(line, in.readLine()); }
            FakeGameServer.write(socket, "PONG\n");
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            client.listRooms(); client.createRoom("Networks", 5); client.join("r1");
            client.leave(); client.start();
            for (int choice = 0; choice < 4; choice++) { client.answer("q1", choice); }
            client.ping();
            assertEquals(MessageType.PONG, recorder.next().type());
            assertTrue(client.isConnected());
            client.close(); server.awaitScript();
        }
    }

    @Test void deliversAllServerTypesInOrderIncludingBufferedHandshakeData() throws Exception {
        List<Message> expected = List.of(
                new Message(MessageType.WELCOME, List.of("alice")),
                new Message(MessageType.PONG, List.of()),
                new Message(MessageType.ERROR, List.of("ALREADY_ANSWERED", "First answer counts")),
                new Message(MessageType.ROOMS, List.of("r1:Networks:2:LOBBY")),
                new Message(MessageType.JOINED, List.of("r1", "Networks", "true")),
                new Message(MessageType.PLAYER_JOINED, List.of("bob")),
                new Message(MessageType.PLAYER_LEFT, List.of("bob")),
                new Message(MessageType.QUESTION, List.of("q1", "1", "1", "π | path \\ \n text", "A", "B", "C", "D", "15")),
                new Message(MessageType.RESULT, List.of("q1", "2", "100", "100")),
                new Message(MessageType.GAME_OVER, List.of("alice", "alice:100;bob:0")));
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertEquals("HELLO|" + TEST_TOKEN, in.readLine());
            StringBuilder batch = new StringBuilder();
            expected.forEach(m -> batch.append(MessageCodec.encode(m)).append('\n'));
            FakeGameServer.write(socket, batch.toString());
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect();
            for (Message message : expected) { assertEquals(message, recorder.next()); }
            assertTrue(recorder.threads.stream().allMatch("quizbattle-tcp-reader"::equals));
            client.close(); server.awaitScript();
        }
    }

    @Test void handlesFragmentedUtf8Messages() throws Exception {
        CountDownLatch prefixObserved = new CountDownLatch(1);
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            byte[] payload = "ROOMS|r1:Révision:1:LOBBY\nPONG\n".getBytes(StandardCharsets.UTF_8);
            // Split in the middle of the two-byte é character.
            int split = 11;
            socket.getOutputStream().write(payload, 0, split);
            socket.getOutputStream().flush();
            assertTrue(prefixObserved.await(3, TimeUnit.SECONDS));
            for (int i = split; i < payload.length; i++) {
                socket.getOutputStream().write(payload[i]);
                socket.getOutputStream().flush();
            }
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            prefixObserved.countDown();
            assertEquals("r1:Révision:1:LOBBY", recorder.next().getField(0));
            assertEquals(MessageType.PONG, recorder.next().type());
            client.close(); server.awaitScript();
        }
    }

    @Test void rejectsBadTokenWithoutLeakingServerText() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertEquals("HELLO|" + TEST_TOKEN, in.readLine());
            FakeGameServer.write(socket, "ERROR|BAD_TOKEN|" + TEST_TOKEN + "\n");
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            IOException error = assertThrows(IOException.class, client::connect);
            assertTrue(error.getMessage().contains("BAD_TOKEN"));
            assertFalse(error.toString().contains(TEST_TOKEN));
            assertNull(error.getCause());
            assertEquals(TcpGameClient.State.CLOSED, client.state());
            server.awaitScript();
        }
    }

    @Test void timesOutAuthenticationAndClosesSocket() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertEquals("HELLO|" + TEST_TOKEN, in.readLine());
            assertNull(in.readLine());
        }); TcpGameClient client = new TcpGameClient("localhost", server.port(), TEST_TOKEN,
                new ClientTimeouts(1000, 150, 0))) {
            assertTrue(assertThrows(IOException.class, client::connect).getMessage().contains("Authentication timed out"));
            assertFalse(client.isConnected()); server.awaitScript();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PONG", "WELCOME", "WELCOME|a", "WELCOME|alice|extra", "ERROR|LOCKED|private", "UNKNOWN|private"})
    void rejectsUnexpectedAuthenticationResponses(String response) throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            in.readLine(); FakeGameServer.write(socket, response + "\n"); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            assertThrows(IOException.class, client::connect);
            assertFalse(client.isAuthenticated()); server.awaitScript();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN|private", "", "PONG|extra", "LOGIN|alice|private", "ROOMS|bad\\x", "ROOMS|bad\\", "ROOMS|bad\rvalue"})
    void terminatesMalformedProtocolSafely(String line) throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); FakeGameServer.write(socket, line + "\n"); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); assertEquals(MessageType.WELCOME, recorder.next().type());
            IOException error = recorder.failure();
            assertNotNull(error); assertFalse(error.toString().contains("private"));
            assertFalse(client.isConnected()); assertTrue(recorder.messages.isEmpty());
            server.awaitScript();
        }
    }

    @Test void boundsOversizedIncomingMessagesBeforeNewline() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            FakeGameServer.write(socket, "ROOMS|" + "é".repeat(2046)); // 4098 bytes, no newline
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertTrue(recorder.failure().getMessage().contains("4096")); server.awaitScript();
        }
    }

    @Test void acceptsExactly4096Bytes() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); FakeGameServer.write(socket, "ROOMS|" + "a".repeat(4090) + "\n");
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertEquals(4090, recorder.next().getField(0).length());
            client.close(); server.awaitScript();
        }
    }

    @Test void rejectsInvalidUtf8() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            socket.getOutputStream().write(new byte[] {(byte) 0xC3, 0x28, '\n'});
            socket.getOutputStream().flush(); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertTrue(recorder.failure().getMessage().contains("UTF-8")); server.awaitScript();
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 4, Integer.MAX_VALUE})
    void rejectsInvalidAnswerIndices(int choice) {
        try (TcpGameClient client = new TcpGameClient("localhost", 1, TEST_TOKEN)) {
            assertThrows(IllegalArgumentException.class, () -> client.answer("q1", choice));
        }
    }

    @Test void validatesInputsAndRestrictsGenericSenderToGameCommands() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient(" ", 1, TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient("http://example", 1, TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient("localhost", 0, TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient("localhost", 65536, TEST_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient("localhost", 1, " "));
        assertThrows(IllegalArgumentException.class, () -> new TcpGameClient("localhost", 1, "a".repeat(4091)));
        assertThrows(IllegalArgumentException.class, () -> new ClientTimeouts(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientTimeouts(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientTimeouts(1, 1, -1));
        try (TcpGameClient client = new TcpGameClient("localhost", 1, TEST_TOKEN)) {
            assertThrows(IOException.class, client::ping);
            assertThrows(IllegalArgumentException.class, () -> client.createRoom("bad|name", 1));
            assertThrows(IllegalArgumentException.class, () -> client.createRoom("a".repeat(25), 1));
            assertThrows(IllegalArgumentException.class, () -> client.createRoom("Valid", 0));
            assertThrows(IllegalArgumentException.class, () -> client.join(""));
            assertThrows(IllegalArgumentException.class, () -> client.answer("q\n1", 0));
            assertThrows(IllegalArgumentException.class, () -> client.join("a".repeat(4096)));
            assertThrows(IllegalArgumentException.class, () -> client.send(new Message(MessageType.LOGIN, List.of("alice", "private"))));
            assertThrows(IllegalArgumentException.class, () -> client.send(new Message(MessageType.PING, List.of("extra"))));
            client.close(); assertThrows(IOException.class, client::connect);
        }
    }

    @Test void reportsServerShutdownAfterDeliveringPendingMessages() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); FakeGameServer.write(socket, "PONG\n");
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertEquals(MessageType.PONG, recorder.next().type());
            assertTrue(recorder.failure().getMessage().contains("disconnected"));
            assertEquals(1, recorder.disconnects.get()); server.awaitScript();
        }
    }

    @Test void reportsTruncatedFinalMessage() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); FakeGameServer.write(socket, "ROOMS|partial");
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertTrue(recorder.failure().getMessage().contains("incomplete")); server.awaitScript();
        }
    }

    @Test void reportsConnectionRefused() throws Exception {
        // Bound but non-listening socket reserves an unused port, avoiding a port reuse race.
        try (java.net.Socket reservation = new java.net.Socket()) {
            reservation.bind(new java.net.InetSocketAddress(InetAddress.getByName("localhost"), 0));
            try (TcpGameClient client = new TcpGameClient("localhost", reservation.getLocalPort(), TEST_TOKEN)) {
                assertTrue(assertThrows(IOException.class, client::connect).getMessage().contains("refused"));
                assertEquals(TcpGameClient.State.CLOSED, client.state());
            }
        }
    }

    @Test void reportsUnknownHost() {
        try (TcpGameClient client = new TcpGameClient("invalid..hostname", 1, TEST_TOKEN)) {
            assertTrue(assertThrows(IOException.class, client::connect).getMessage().contains("resolved"));
        }
    }

    @Test void serializesConcurrentWritesWithoutInterleaving() throws Exception {
        int count = 80;
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            java.util.Set<String> received = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) {
                Message m = MessageCodec.decode(in.readLine());
                assertEquals(MessageType.JOIN, m.type());
                assertEquals(1, m.fields().size());
                assertTrue(received.add(m.getField(0)));
            }
            for (int i = 0; i < count; i++) { assertTrue(received.contains("r" + i + "|" + "é".repeat(200))); }
            FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<java.util.concurrent.Future<?>> results = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int id = i;
                    results.add(pool.submit(() -> {
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        client.join("r" + id + "|" + "é".repeat(200)); return null;
                    }));
                }
                start.countDown();
                for (var result : results) { result.get(4, TimeUnit.SECONDS); }
                assertEquals(MessageType.PONG, recorder.next().type());
            } finally {
                pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            }
            client.close(); server.awaitScript();
        }
    }

    @Test void closeCancelsAuthenticationAndRejectsConcurrentConnect() throws Exception {
        CountDownLatch helloSeen = new CountDownLatch(1);
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            in.readLine(); helloSeen.countDown(); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var connecting = executor.submit(() -> assertThrows(IOException.class, client::connect));
                assertTrue(helloSeen.await(3, TimeUnit.SECONDS));
                assertThrows(IOException.class, client::connect);
                client.close(); connecting.get(3, TimeUnit.SECONDS);
                assertEquals(TcpGameClient.State.CLOSED, client.state()); server.awaitScript();
            } finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void isolatesListenerExceptionsAndAllowsCloseFromCallback() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder();
            ClientMessageListener removed = m -> fail("Removed listener called");
            client.addListener(removed); client.removeListener(removed);
            client.addListener(m -> { throw new IllegalStateException("Listener failure"); });
            client.addListener(recorder);
            client.addListener(m -> { if (m.type() == MessageType.PONG) { client.close(); } });
            client.connect(); recorder.next();
            assertEquals(MessageType.PONG, recorder.next().type());
            assertNull(recorder.failure()); server.awaitScript();
        }
    }

    @Test void supportsConfiguredGameplayReadTimeout() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in); assertNull(in.readLine());
        }); TcpGameClient client = new TcpGameClient("localhost", server.port(), TEST_TOKEN,
                new ClientTimeouts(1000, 1000, 150))) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next();
            assertTrue(recorder.failure().getMessage().contains("Timed out")); server.awaitScript();
        }
    }

    @Test void genericSenderEscapesFieldsUsingSharedCodec() throws Exception {
        Message command = new Message(MessageType.JOIN, List.of("room|path\\value"));
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            welcome(socket, in);
            assertEquals(MessageCodec.encode(command), in.readLine());
            FakeGameServer.write(socket, "PONG\n"); assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            Recorder recorder = new Recorder(); client.addListener(recorder);
            client.connect(); recorder.next(); client.send(command);
            assertEquals(MessageType.PONG, recorder.next().type());
            client.close(); server.awaitScript();
        }
    }

    @Test void closesOnAuthenticationEof() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            assertEquals("HELLO|" + TEST_TOKEN, in.readLine());
        }); TcpGameClient client = client(server)) {
            assertTrue(assertThrows(IOException.class, client::connect).getMessage().contains("authentication"));
            assertFalse(client.isConnected()); server.awaitScript();
        }
    }

    @Test void boundsAuthenticationMessageAndDoesNotEchoIt() throws Exception {
        try (FakeGameServer server = new FakeGameServer((socket, in) -> {
            in.readLine(); FakeGameServer.write(socket, "WELCOME|" + TEST_TOKEN.repeat(300));
            assertNull(in.readLine());
        }); TcpGameClient client = client(server)) {
            IOException error = assertThrows(IOException.class, client::connect);
            assertTrue(error.getMessage().contains("4096"));
            assertFalse(error.toString().contains(TEST_TOKEN)); server.awaitScript();
        }
    }
}
