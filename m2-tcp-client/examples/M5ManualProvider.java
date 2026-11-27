package com.quizbattle.integration.example;

import com.quizbattle.client.ClientTimeouts;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.GuiServiceProvider;
import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ServerFinder;
import com.quizbattle.security.TlsSecureLoginClient;
import java.io.IOException;
import java.util.List;

/**
 * Proposed app-owned wiring, compiled only by the opt-in integration probe.
 * Not in M2 production sources, not registered with ServiceLoader, and not a UDP substitute.
 */
public final class M5ManualProvider implements GuiServiceProvider {
    @Override public GameClientService create(AppConfig config) throws IOException {
        String certificate = config.getString("security.server.cert.path", "secrets/server-cert.pem");
        if (certificate.isBlank()) { throw new IOException("Configure the public server certificate path."); }
        ServerFinder manualOnly = timeout -> List.of(); // Discovery intentionally unavailable until M3 exists.
        return new GameClientService(manualOnly, new TlsSecureLoginClient(certificate), null, ClientTimeouts.fromConfig(config));
    }
}
