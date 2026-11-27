package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ClientApplication;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import java.util.List;

/** Explicit mock mode only; never installed as a production provider. */
public final class MockGuiLauncher {
    private MockGuiLauncher() { }
    public static void main(String[] args) throws Exception {
        FakeSecureLoginClient auth = new FakeSecureLoginClient();
        try (GuiMockScenario scenario = new GuiMockScenario(auth, true);
             FakeGameServer server = new FakeGameServer(scenario::play, Integer.MAX_VALUE);
             GameClientService service = new GameClientService(
                     new FakeServerFinder(List.of(new ServerInfo("MOCK Quiz server", "localhost", server.port(), server.port()))),
                     auth, () -> { FakeLiveFeedReceiver feed = new FakeLiveFeedReceiver(); scenario.feed = feed; return feed; })) {
            scenario.fullGame = true;
            ClientApplication.launchInjected(service, "MOCK MODE · TEST SERVICES", args);
            service.close();
            if (!service.awaitTermination(Duration.ofSeconds(3))) { throw new IllegalStateException("Mock client workers did not stop."); }
        }
    }
}
