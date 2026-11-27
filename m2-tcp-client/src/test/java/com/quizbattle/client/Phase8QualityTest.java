package com.quizbattle.client;

import com.quizbattle.client.service.AuthenticationFailure;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ConsoleClient;
import com.quizbattle.common.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class Phase8QualityTest {
    private static <T> T done(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void gate(CountDownLatch latch) throws IOException {
        try { if (!latch.await(4, TimeUnit.SECONDS)) { throw new IOException("Test deadline expired"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Test interrupted"); }
    }
    private static void expectFailure(CompletableFuture<Void> request) {
        var error = assertThrows(ExecutionException.class, () -> done(request));
        assertEquals("USER_EXISTS", assertInstanceOf(AuthenticationFailure.class, error.getCause()).code());
    }
    @ParameterizedTest @CsvSource({"localhost:5050,5050,hostname", "localhost,70000,port"})
    void consoleManualAddressValidationMatchesGuiAndDoesNotStartAuthentication(String host, String port, String message) throws Exception {
        var auth = new FakeSecureLoginClient();
        var output = new java.io.StringWriter();
        try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth, null)) {
            var input = new java.io.StringReader("2\n" + host + "\n" + port + "\n5443\nexit\n");
            new ConsoleClient(service, input, new java.io.PrintWriter(output, true), AppConfig.load("config/client.properties")).run();
            assertTrue(output.toString().contains("Invalid input:")); assertTrue(output.toString().contains(message));
            assertEquals(0, auth.logins.get()); assertFalse(service.state().snapshot().authenticated());
        }
    }
    private static final class GatedRegistration implements SecureLoginClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean reject;
        volatile char[] supplied;
        GatedRegistration(boolean reject) { this.reject = reject; }
        @Override public void register(String host, int port, String name, char[] password) throws IOException {
            supplied = password; entered.countDown(); gate(release);
            if (reject) { throw new IOException("Server error: USER_EXISTS - untrusted private data"); }
        }
        @Override public String login(String host, int port, String name, char[] password) { return FakeSecureLoginClient.TOKEN; }
    }
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void delayedRegistrationCannotOverwriteANewerConnectionOrDisconnect(boolean reject, boolean reconnect) throws Exception {
        var auth = new GatedRegistration(reject);
        try (FakeGameServer server = new FakeGameServer((socket, input) -> {
            assertTrue(input.readLine().startsWith("HELLO|")); FakeGameServer.write(socket, "WELCOME|alice\n"); assertNull(input.readLine());
        }); GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth, null)) {
            var info = new ServerInfo("Fixture", "localhost", server.port(), server.port());
            char[] password = new char[]{'x'}; var request = service.register(info, "newuser", password);
            assertTrue(auth.entered.await(2, TimeUnit.SECONDS));
            if (reconnect) { done(service.connect(info, FakeSecureLoginClient.TOKEN)); }
            else { done(service.disconnect()); }
            var before = service.state().snapshot(); auth.release.countDown();
            if (reject) { expectFailure(request); } else { done(request); }
            var after = service.state().snapshot();
            assertEquals(before, after); assertEquals(reconnect, after.authenticated());
            assertTrue(new String(password).chars().allMatch(c -> c == 0));
            assertTrue(new String(auth.supplied).chars().allMatch(c -> c == 0));
            done(service.disconnect());
            if (reconnect) { server.awaitScript(); }
        } finally { auth.release.countDown(); }
    }
    @Test void anOlderRegistrationSuccessCannotEraseTheLatestRegistrationFailure() throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1), newEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
        SecureLoginClient auth = new SecureLoginClient() {
            @Override public void register(String host, int port, String name, char[] password) throws IOException {
                if (name.equals("olduser")) { oldEntered.countDown(); gate(releaseOld); }
                else { newEntered.countDown(); gate(releaseNew); throw new IOException("ERROR|USER_EXISTS|private upstream data"); }
            }
            @Override public String login(String h, int p, String n, char[] password) { return FakeSecureLoginClient.TOKEN; }
        };
        try (GameClientService service = new GameClientService(new FakeServerFinder(List.of()), auth, null)) {
            var info = new ServerInfo("Fixture", "localhost", 1, 2);
            var old = service.register(info, "olduser", new char[]{'x'}); assertTrue(oldEntered.await(2, TimeUnit.SECONDS));
            var newest = service.register(info, "newuser", new char[]{'x'}); assertTrue(newEntered.await(2, TimeUnit.SECONDS));
            releaseNew.countDown(); expectFailure(newest); var before = service.state().snapshot();
            assertTrue(before.notice().contains("already exists")); releaseOld.countDown(); done(old);
            assertEquals(before, service.state().snapshot());
        } finally { releaseOld.countDown(); releaseNew.countDown(); }
    }
    @Test void cancelledRegistrationCompletionIsSilentEvenAfterItsProviderReturns() throws Exception {
        var auth = new GatedRegistration(false);
        CountDownLatch finderEntered = new CountDownLatch(1), releaseFinder = new CountDownLatch(1);
        AtomicInteger finds = new AtomicInteger();
        ServerFinder finder = timeout -> {
            if (finds.getAndIncrement() == 0) { finderEntered.countDown(); gate(releaseFinder); }
            return List.of();
        };
        try (GameClientService service = new GameClientService(finder, auth, null)) {
            var heldFinder = service.discoverServers(Duration.ofSeconds(1)); assertTrue(finderEntered.await(2, TimeUnit.SECONDS));
            var registration = service.register(new ServerInfo("Fixture", "localhost", 1, 2), "newuser", new char[]{'x'});
            assertTrue(auth.entered.await(2, TimeUnit.SECONDS)); assertTrue(registration.cancel(false));
            // Both I/O workers are occupied. This queued discovery can run only after
            // registration enqueues its completion, giving a deterministic event barrier.
            var barrier = service.discoverServers(Duration.ofSeconds(1));
            var before = service.state().snapshot(); auth.release.countDown(); done(barrier);
            assertEquals(before, service.state().snapshot()); assertTrue(registration.isCancelled());
            releaseFinder.countDown(); done(heldFinder);
        } finally { auth.release.countDown(); releaseFinder.countDown(); }
    }
}
