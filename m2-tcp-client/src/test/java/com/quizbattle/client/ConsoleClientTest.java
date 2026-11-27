package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ConsoleClient;
import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ServerInfo;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConsoleClientTest {
    private static final class Input implements ConsoleClient.Input {
        private static final String EOF = "<end-of-input>";
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        int passwordReads;
        void send(String... values) { for (String value : values) { lines.add(value); } }
        void eof() { lines.add(EOF); }
        @Override public String readLine() throws IOException {
            try {
                String line = lines.poll(6, TimeUnit.SECONDS);
                if (line == null) { throw new IOException("Test input deadline expired"); }
                return line.equals(EOF) ? null : line;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Input interrupted"); }
        }
        @Override public char[] readPassword() throws IOException {
            passwordReads++; String line = readLine(); return line == null ? null : line.toCharArray();
        }
    }

    private static final class Output extends Writer {
        private final StringBuilder content = new StringBuilder();
        @Override public synchronized void write(char[] chars, int offset, int length) {
            content.append(chars, offset, length); notifyAll();
        }
        @Override public void flush() { }
        @Override public void close() { }
        synchronized String text() { return content.toString(); }
        synchronized void await(String text) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (content.indexOf(text) < 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { fail("Missing console text: " + text + "\nOutput:\n" + content); }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }

    private static final class Rig implements AutoCloseable {
        final Input input = new Input(); final Output output = new Output();
        final FakeGameServer server;
        final GameClientService service;
        final FakeSecureLoginClient auth = new FakeSecureLoginClient();
        final ConsoleClient console;
        final java.util.concurrent.ExecutorService runner = Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<?> finished;
        final Path config;
        Rig(FakeGameServer.Script script) throws Exception {
            server = new FakeGameServer(script);
            service = new GameClientService(new FakeServerFinder(List.of(new ServerInfo("MOCK server", "localhost", server.port(), server.port()))),
                    auth, FakeLiveFeedReceiver::new);
            config = Files.createTempFile("quizbattle-console-test-", ".properties");
            Files.writeString(config, "client.server.host=localhost\nclient.gateway.port=" + server.port() + "\nclient.tls.port=" + server.port());
            console = new ConsoleClient(service, input, new PrintWriter(output, true), AppConfig.load(config.toString()));
            finished = runner.submit(() -> { console.run(); return null; });
            output.await("CAMPUS QUIZ BATTLE");
        }
        void login() throws Exception {
            input.send("1"); output.await("SERVER SELECTION"); input.send("1"); output.await("AUTHENTICATION");
            input.send("1", "alice", "console-demo-password"); output.await("GAME LOBBY");
        }
        @Override public void close() throws Exception {
            input.eof(); console.close();
            try { finished.get(3, TimeUnit.SECONDS); }
            finally {
                runner.shutdownNow(); assertTrue(runner.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(service.awaitTermination(Duration.ofSeconds(3))); server.close(); Files.deleteIfExists(config);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "abc", "0", "-1", "5", "1.5", "2147483648"})
    void rejectsInvalidAnswerChoices(String value) {
        assertThrows(IllegalArgumentException.class, () -> ConsoleClient.answerIndex(value));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
    void convertsDisplayedChoicesToProtocolIndices(int choice) {
        assertEquals(choice - 1, ConsoleClient.answerIndex(Integer.toString(choice)));
    }

    @Test void validatesMenusAndExitsGracefully() throws Exception {
        try (Rig rig = new Rig((socket, input) -> { })) {
            rig.input.send("not a menu option"); rig.output.await("Invalid input:");
            rig.input.send("3"); rig.finished.get(3, TimeUnit.SECONDS);
            assertTrue(rig.service.awaitTermination(Duration.ofSeconds(2)));
            assertEquals(0, rig.auth.logins.get());
        }
    }

    @Test void supportsRegistrationAndCompleteScriptedGameplay() throws Exception {
        try (Rig rig = new Rig(MockQuizDemo::play)) {
            rig.input.send("1"); rig.output.await("SERVER SELECTION");
            rig.input.send("1"); rig.output.await("AUTHENTICATION");
            rig.input.send("2", "alice", "console-demo-password"); rig.output.await("Registration succeeded");
            rig.input.send("1", "alice", "console-demo-password"); rig.output.await("GAME LOBBY");
            rig.input.send("2"); rig.output.await("Room name"); rig.input.send("Networks", "2");
            rig.output.await("WAITING ROOM"); rig.output.await("bob");
            rig.input.send("start"); rig.output.await("Your answer: answer q1 <1-4>");
            rig.input.send("answer q1 5"); rig.output.await("Invalid input:");
            rig.input.send("answer old 2"); rig.output.await("Answer is outdated");
            rig.input.send("answer q1 2"); rig.output.await("Your answer: answer q2 <1-4>");
            // This old answer must never be sent as an answer to q2.
            rig.input.send("answer q1 1");
            rig.input.send("answer q2 1"); rig.output.await("BATTLE COMPLETE"); rig.output.await("Winner: alice");
            assertTrue(rig.output.text().contains("Correct!")); assertTrue(rig.output.text().contains("Total score: 230"));
            assertTrue(rig.output.text().contains("Points awarded: 130")); assertTrue(rig.output.text().contains("Round"));
            assertFalse(rig.output.text().contains("console-demo-password"));
            assertFalse(rig.output.text().contains(FakeSecureLoginClient.TOKEN)); assertEquals(2, rig.input.passwordReads);
            rig.input.send("1"); rig.output.await("returned locally to lobby");
            rig.input.send("5"); rig.finished.get(3, TimeUnit.SECONDS); rig.server.awaitScript();
        }
    }

    @Test void manualServerEntryUsesConfiguredPorts() throws Exception {
        try (Rig rig = new Rig(MockQuizDemo::play)) {
            rig.input.send("2"); rig.output.await("Server IP/hostname");
            rig.input.send("", "", ""); rig.output.await("AUTHENTICATION: Manual server");
            rig.input.send("1", "alice", "console-demo-password"); rig.output.await("GAME LOBBY");
            assertEquals(rig.server.port(), rig.auth.port);
            rig.input.send("5"); rig.finished.get(3, TimeUnit.SECONDS); rig.server.awaitScript();
        }
    }

    @Test void newQuestionAndServerDisconnectionDisplayWithoutExtraInput() throws Exception {
        CountDownLatch closeServer = new CountDownLatch(1);
        try (Rig rig = new Rig((socket, in) -> {
            assertEquals("HELLO|" + FakeSecureLoginClient.TOKEN, in.readLine());
            FakeGameServer.write(socket, "WELCOME|alice\n");
            assertEquals("LIST_ROOMS", in.readLine()); FakeGameServer.write(socket, "ROOMS|r1:Networks:1:LOBBY\n");
            assertEquals("JOIN|r1", in.readLine());
            FakeGameServer.write(socket, "JOINED|r1|Networks|false\n" + MockQuizDemo.question(1));
            assertTrue(closeServer.await(4, TimeUnit.SECONDS));
        })) {
            rig.login(); rig.input.send("3"); rig.output.await("Room ID:"); rig.input.send("r1");
            rig.output.await("Your answer: answer q1 <1-4>"); // No input needed to display the event.
            closeServer.countDown(); rig.output.await("Game server connection lost");
            rig.input.send("3"); rig.finished.get(3, TimeUnit.SECONDS); rig.server.awaitScript();
        }
    }

    @Test void inputEndingAtWelcomeOrPasswordPromptClosesResources() throws Exception {
        try (Rig rig = new Rig((socket, input) -> { })) {
            rig.input.eof(); rig.finished.get(3, TimeUnit.SECONDS);
        }
        try (Rig rig = new Rig((socket, input) -> { })) {
            rig.input.send("1"); rig.output.await("SERVER SELECTION"); rig.input.send("1"); rig.output.await("AUTHENTICATION");
            rig.input.send("1", "alice"); rig.output.await("Password:"); rig.input.eof(); rig.finished.get(3, TimeUnit.SECONDS);
            assertEquals(0, rig.auth.logins.get());
        }
    }
}
