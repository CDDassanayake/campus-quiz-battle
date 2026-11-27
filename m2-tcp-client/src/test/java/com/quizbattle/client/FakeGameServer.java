package com.quizbattle.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One scripted loopback connection, bound to an OS-assigned port. Tests only. */
final class FakeGameServer implements AutoCloseable {
    @FunctionalInterface
    interface Script { void run(Socket socket, BufferedReader input) throws Exception; }

    private final ServerSocket server;
    private final Thread worker;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile Socket connection;
    private volatile boolean closed;

    FakeGameServer(Script script) throws IOException {
        this(script, 1);
    }
    FakeGameServer(Script script, int connections) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
        worker = new Thread(() -> {
            try {
                for (int i = 0; i < connections && !closed; i++) {
                    try (Socket socket = server.accept()) {
                        connection = socket;
                        if (closed) { return; }
                        socket.setSoTimeout(5000);
                        script.run(socket, new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
                    }
                }
                completion.complete(null);
            } catch (Throwable e) {
                completion.completeExceptionally(e);
            }
        }, "fake-game-server");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return server.getLocalPort(); }

    static void write(Socket socket, String lines) throws IOException {
        socket.getOutputStream().write(lines.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    void awaitScript() throws Exception { completion.get(6, TimeUnit.SECONDS); }

    @Override
    public void close() throws Exception {
        closed = true;
        server.close();
        Socket current = connection;
        if (current != null) { current.close(); }
        worker.join(2000);
        if (worker.isAlive()) { throw new AssertionError("Fake server thread did not stop."); }
    }
}
