package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import java.util.Locale;

/** Derived display data, never a second connection state machine. */
public record ConnectionPresentation(String text, String styleClass) {
    public static ConnectionPresentation from(Snapshot state) {
        if (state.status() == GameStatus.CONNECTING) { return new ConnectionPresentation("Connecting", "status-pending"); }
        if (state.status() == GameStatus.AUTHENTICATING) { return new ConnectionPresentation("Authenticating", "status-pending"); }
        if (state.authenticated()) { return new ConnectionPresentation("Connected", "status-connected"); }
        String notice = state.notice() == null ? "" : state.notice().toLowerCase(Locale.ROOT);
        if (notice.contains("connection lost") || notice.contains("failed to send")) {
            return new ConnectionPresentation("Connection lost", "status-lost");
        }
        return new ConnectionPresentation("Disconnected", "status-disconnected");
    }
}
