package com.quizbattle.client;

import com.quizbattle.common.ServerFinder;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import java.util.List;

public final class FakeServerFinder implements ServerFinder {
    private final List<ServerInfo> servers;
    public volatile Duration requestedTimeout;
    public volatile List<ServerInfo> response;
    public volatile boolean fail;
    public volatile java.util.concurrent.CountDownLatch gate;
    public final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    public FakeServerFinder(List<ServerInfo> servers) { this.servers = List.copyOf(servers); }
    @Override public List<ServerInfo> find(Duration timeout) throws java.io.IOException {
        requestedTimeout = timeout; requests.incrementAndGet();
        var hold = gate;
        if (hold != null) {
            try { if (!hold.await(4, java.util.concurrent.TimeUnit.SECONDS)) { throw new java.io.IOException("Mock discovery timeout"); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Mock discovery interrupted"); }
        }
        if (fail) { throw new java.io.IOException("Mock discovery unavailable"); }
        return response == null ? servers : List.copyOf(response);
    }
}
