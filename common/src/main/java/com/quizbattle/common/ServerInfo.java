package com.quizbattle.common;

/**
 * Information about a discovered Quiz Battle server.
 * Returned by ServerFinder and used by ServerAnnouncer.
 */
public record ServerInfo(String name, String host, int gatewayPort, int tlsPort) {
    
    public ServerInfo {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Server name cannot be null or empty");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (gatewayPort <= 0 || gatewayPort > 65535) {
            throw new IllegalArgumentException("Invalid gateway port: " + gatewayPort);
        }
        if (tlsPort <= 0 || tlsPort > 65535) {
            throw new IllegalArgumentException("Invalid TLS port: " + tlsPort);
        }
    }
}
