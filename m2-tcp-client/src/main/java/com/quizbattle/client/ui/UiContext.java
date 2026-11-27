package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** Presentation dependencies. A disconnected foundation has no service provider. */
public final class UiContext implements AutoCloseable {
    private final GameClientService service;
    private final ClientGameState state;
    private final String mode;
    private final BooleanProperty reducedMotion = new SimpleBooleanProperty(false);
    private final AppConfig config;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService actions = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quizbattle-ui-actions"); thread.setDaemon(true); return thread;
    });
    private ServerInfo selectedServer;
    private String suggestedUsername = "";
    private boolean logoutRequested;
    private CompletableFuture<List<ServerInfo>> discovery;

    public UiContext(GameClientService service, String mode) {
        this(service, mode, null);
    }
    public UiContext(GameClientService service, String mode, AppConfig config) {
        this.service = service; this.mode = Objects.requireNonNull(mode);
        this.config = config;
        state = service == null ? new ClientGameState() : service.state();
    }
    public Optional<GameClientService> service() { return Optional.ofNullable(service); }
    public ClientGameState state() { return state; }
    public String mode() { return mode; }
    public BooleanProperty reducedMotionProperty() { return reducedMotion; }
    public boolean reducedMotion() { return reducedMotion.get(); }
    public Optional<ServerInfo> selectedServer() { return Optional.ofNullable(selectedServer); }
    public void selectServer(ServerInfo server) { FormValidation.host(server.host()); selectedServer = server; }
    public String defaultHost() { return config == null ? "" : config.getString("client.server.host", ""); }
    public int defaultGatewayPort() { return config == null ? 5050 : config.getInt("client.gateway.port", 5050); }
    public int defaultTlsPort() { return config == null ? 5443 : config.getInt("client.tls.port", 5443); }
    public String suggestedUsername() { return suggestedUsername; }
    public void suggestUsername(String name) { suggestedUsername = name; }
    public void beginAuthentication() { logoutRequested = false; }
    public boolean logoutRequested() { return logoutRequested; }
    public GameClientService requireService() {
        if (service == null) { throw new IllegalStateException("Network services are not configured for this client."); }
        return service;
    }
    /** Serializes supplier invocation off the FX thread, including disconnect and close. */
    public <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> action) {
        if (closed.get()) { return CompletableFuture.failedFuture(new IllegalStateException("Client is closed.")); }
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (closed.get()) { throw new IllegalStateException("Client is closed."); }
                return action.get();
            }, actions).thenCompose(result -> result);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(new IllegalStateException("Client is closed.")); }
    }
    /** Reuses an in-flight scan across screen changes; common has no discovery cancel API. */
    public CompletableFuture<List<ServerInfo>> discoverServers() {
        if (discovery == null || discovery.isDone()) {
            discovery = invoke(() -> requireService().discoverServers(Duration.ofSeconds(2)));
        }
        return discovery;
    }
    public CompletableFuture<Void> disconnect() {
        logoutRequested = true;
        return invoke(() -> service == null ? CompletableFuture.completedFuture(null) : service.disconnect());
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        if (service != null) { actions.execute(service::close); }
        actions.shutdown();
    }
    /** Launcher/test cleanup only; never call on the JavaFX Application Thread. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        return actions.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                && (service == null || service.awaitTermination(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
    }
}
