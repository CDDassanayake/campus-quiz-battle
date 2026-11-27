package com.quizbattle.client;

import com.quizbattle.common.SecureLoginClient;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicitly fake authentication; no TLS, hashing, certificate or real credential checks. */
public final class FakeSecureLoginClient implements SecureLoginClient {
    public static final String TOKEN = "synthetic-test-token";
    public volatile boolean reject;
    public volatile String failureCode;
    public volatile java.util.concurrent.CountDownLatch loginGate;
    public volatile java.util.concurrent.CountDownLatch registrationGate;
    public volatile String callingThread;
    public final java.util.concurrent.CountDownLatch loginEntered = new java.util.concurrent.CountDownLatch(1);
    public volatile String host;
    public volatile int port;
    public volatile String username;
    public volatile char[] suppliedPassword;
    public final AtomicInteger logins = new AtomicInteger();
    public final AtomicInteger registrations = new AtomicInteger();

    @Override public void register(String host, int port, String username, char[] password) throws IOException {
        capture(host, port, username, password); registrations.incrementAndGet();
        waitFor(registrationGate);
        if (failureCode != null) { throw new IOException("ERROR|" + failureCode + "|Untrusted upstream text"); }
        if (reject) { throw new IOException("Untrusted upstream error may contain secrets"); }
    }
    @Override public String login(String host, int port, String username, char[] password) throws IOException {
        capture(host, port, username, password); logins.incrementAndGet();
        loginEntered.countDown();
        waitFor(loginGate);
        if (failureCode != null) { throw new IOException("ERROR|" + failureCode + "|Untrusted upstream text"); }
        if (reject) { throw new IOException("Untrusted upstream error may contain secrets"); }
        return TOKEN;
    }
    private void capture(String host, int port, String username, char[] password) {
        this.host = host; this.port = port; this.username = username; this.suppliedPassword = password;
        this.callingThread = Thread.currentThread().getName();
    }
    private static void waitFor(java.util.concurrent.CountDownLatch gate) throws IOException {
        if (gate == null) { return; }
        try { if (!gate.await(8, java.util.concurrent.TimeUnit.SECONDS)) { throw new IOException("Mock authentication deadline expired"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Mock authentication cancelled"); }
    }
}
