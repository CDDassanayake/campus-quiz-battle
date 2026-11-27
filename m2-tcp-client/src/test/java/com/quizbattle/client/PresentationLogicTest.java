package com.quizbattle.client;

import com.quizbattle.client.model.ClientGameState;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ConnectionPresentation;
import com.quizbattle.client.ui.NavigationHistory;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiStateSubscription;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** No JavaFX toolkit, display server, TestFX or Monocle is needed for these tests. */
class PresentationLogicTest {
    @ParameterizedTest @EnumSource(Screen.class)
    void routesEnforceAuthentication(Screen requested) {
        NavigationHistory navigation = new NavigationHistory();
        assertEquals(requested.requiresAuthentication() ? Screen.LOGIN : requested, navigation.navigate(requested, false));
        assertEquals(requested, navigation.navigate(requested, true));
    }

    @Test void backNavigationSkipsScreensThatAreNoLongerAuthorized() {
        NavigationHistory navigation = new NavigationHistory();
        navigation.navigate(Screen.SERVER_SELECTION, false); navigation.navigate(Screen.LOBBY, true);
        navigation.navigate(Screen.QUIZ, true); navigation.navigate(Screen.SETTINGS, true);
        assertEquals(Screen.SERVER_SELECTION, navigation.back(false)); assertEquals(Screen.WELCOME, navigation.back(false));
    }

    @Test void repeatingCurrentRouteDoesNotDuplicateHistory() {
        NavigationHistory navigation = new NavigationHistory();
        navigation.navigate(Screen.SETTINGS, false); navigation.navigate(Screen.SETTINGS, false);
        assertEquals(Screen.WELCOME, navigation.back(false)); assertEquals(Screen.WELCOME, navigation.back(false));
    }

    @Test void connectionPresentationUsesActualApplicationState() {
        ClientGameState state = new ClientGameState();
        assertEquals("Disconnected", ConnectionPresentation.from(state.snapshot()).text());
        state.connecting(new ServerInfo("Fixture", "localhost", 1, 2));
        assertEquals("Connecting", ConnectionPresentation.from(state.snapshot()).text());
        state.authenticating(); assertEquals("Authenticating", ConnectionPresentation.from(state.snapshot()).text());
        state.receive(MessageCodec.decode("WELCOME|alice"));
        assertEquals("status-connected", ConnectionPresentation.from(state.snapshot()).styleClass());
        state.disconnected("Game server connection lost. Log in again to reconnect.");
        assertEquals("Connection lost", ConnectionPresentation.from(state.snapshot()).text());
        state.disconnected("Disconnected."); assertEquals("status-disconnected", ConnectionPresentation.from(state.snapshot()).styleClass());
    }

    @Test void subscriptionDispatchesSnapshotsAndDropsQueuedWorkAfterDisposal() {
        ClientGameState state = new ClientGameState(); List<Runnable> queue = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        UiStateSubscription subscription = new UiStateSubscription(state, queue::add, s -> versions.add(s.version()));
        state.notification("First");
        assertTrue(versions.isEmpty()); queue.remove(0).run(); queue.remove(0).run();
        assertEquals(List.of(0L, 1L), versions);
        state.notification("Queued before disposal"); subscription.close(); subscription.close();
        queue.remove(0).run(); state.notification("After disposal");
        assertTrue(queue.isEmpty()); assertEquals(2, versions.size());
    }

    @Test void lateInitialSnapshotCannotOverwriteANewerVersion() {
        ClientGameState state = new ClientGameState(); List<Runnable> queue = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        try (UiStateSubscription subscription = new UiStateSubscription(state, queue::add, s -> versions.add(s.version()))) {
            state.notification("Newer"); queue.get(1).run(); queue.get(0).run();
            assertEquals(List.of(1L), versions);
        }
    }

    @Test void backgroundUpdatesRunOnlyThroughTheChosenUiDispatcher() throws Exception {
        ClientGameState state = new ClientGameState();
        var ui = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-ui-dispatcher"));
        List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (UiStateSubscription subscription = new UiStateSubscription(state, ui::execute,
                snapshot -> threads.add(Thread.currentThread().getName()))) {
            state.notification("Background event"); ui.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(List.of("test-ui-dispatcher", "test-ui-dispatcher"), threads);
        } finally { ui.shutdownNow(); assertTrue(ui.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void uiContextOwnsServiceLifecycleAndRetainsItsActualState() throws Exception {
        GameClientService service = new GameClientService(new FakeServerFinder(List.of()), new FakeSecureLoginClient(), null);
        UiContext context = new UiContext(service, "MOCK MODE");
        assertSame(service.state(), context.state()); assertEquals(service, context.service().orElseThrow());
        context.close(); context.close(); assertTrue(service.awaitTermination(Duration.ofSeconds(2)));
        assertTrue(service.ping().isCompletedExceptionally());
    }

    @Test void foundationHasNoFakeNetworkServiceOrConnectedStatus() {
        try (UiContext context = new UiContext(null, "GUI FOUNDATION")) {
            assertTrue(context.service().isEmpty());
            assertFalse(context.state().snapshot().connected());
            assertEquals("Disconnected", ConnectionPresentation.from(context.state().snapshot()).text());
        }
    }

    @ParameterizedTest @EnumSource(Screen.class)
    void everyRouteHasAWellFormedLocalLayout(Screen screen) throws Exception {
        try (var source = Screen.class.getResourceAsStream(screen.resource())) {
            assertNotNull(source);
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            assertNotNull(factory.newDocumentBuilder().parse(source).getDocumentElement());
        }
    }
}
