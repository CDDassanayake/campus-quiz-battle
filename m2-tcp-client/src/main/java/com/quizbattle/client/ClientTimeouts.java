package com.quizbattle.client;

/** Milliseconds. Zero gameplay timeout allows an idle persistent connection. */
public record ClientTimeouts(int connectMillis, int authenticationMillis, int readMillis) {
    public ClientTimeouts {
        if (connectMillis <= 0 || authenticationMillis <= 0 || readMillis < 0) {
            throw new IllegalArgumentException("Connect/authentication timeouts must be positive; read timeout must be nonnegative.");
        }
    }

    public static ClientTimeouts defaults() {
        return new ClientTimeouts(3000, 4000, 0);
    }

    /** Optional M2 settings; does not alter the shared configuration or M5's own TLS deadlines. */
    public static ClientTimeouts fromConfig(com.quizbattle.common.AppConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new ClientTimeouts(setting(config, "client.connect.timeout.ms", 3000),
                setting(config, "client.authentication.timeout.ms", 4000), setting(config, "client.read.timeout.ms", 0));
    }
    private static int setting(com.quizbattle.common.AppConfig config, String key, int fallback) {
        String raw = config.getString(key);
        if (raw == null) { return fallback; }
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer setting: " + key); }
    }
}
