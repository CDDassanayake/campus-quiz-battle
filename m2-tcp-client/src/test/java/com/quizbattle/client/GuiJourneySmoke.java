package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.components.PasswordInput;
import com.quizbattle.client.ui.components.StatusIndicator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.quizbattle.common.ServerInfo;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;

/** Real UI actions with gated mock services and real TCP; never loaded in normal mode. */
final class GuiJourneySmoke implements AutoCloseable {
    @FunctionalInterface interface Capture { void save(Parent root, String name) throws Exception; }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private final Stage stage;
    private final UiContext context;
    private final GameClientService service;
    private final GuiMockScenario scenario;
    private final FakeSecureLoginClient auth;
    private final FakeServerFinder finder;
    private final ServerInfo server;
    private final BiConsumer<Boolean, String> check;
    private final Capture capture;
    private final Consumer<Throwable> failure;
    private final Runnable finished;
    private final SceneManager scenes;
    private PauseTransition pulse;
    private int baseline;
    private double wideWidth;
    private double wideHeight;
    private boolean closed;

    GuiJourneySmoke(Stage stage, UiContext context, GameClientService service, GuiMockScenario scenario,
                    FakeSecureLoginClient auth, FakeServerFinder finder, ServerInfo server, BiConsumer<Boolean, String> check,
                    Capture capture, Consumer<Throwable> failure, Runnable finished) throws Exception {
        this.stage = stage; this.context = context; this.service = service; this.scenario = scenario;
        this.auth = auth; this.finder = finder; this.server = server; this.check = check; this.capture = capture; this.failure = failure; this.finished = finished;
        service.state().disconnected("Disconnected."); context.reducedMotionProperty().set(true);
        scenes = new SceneManager(stage, context);
        Scene scene = new Scene(scenes.root(), 1280, 800); scene.getStylesheets().addAll(SceneManager.stylesheets());
        stage.setScene(scene); stage.setWidth(1296); stage.setHeight(839);
    }
    void start() { later(() -> { wideWidth = stage.getWidth(); wideHeight = stage.getHeight(); click("findBattle"); await(() -> has("select-server-0"), this::discovery); }); }
    private void discovery() throws Exception {
        check.accept(scenes.currentScreen() == Screen.SERVER_SELECTION, "Journey: automatic discovery renders the mock server card"); save("discovery.png");
        minimum("discovery-minimum.png", "manualSelect", this::manualSelection);
    }
    private void manualSelection() throws Exception {
        check.accept(status().equals("Disconnected"), "Journey: discovery alone never claims a TCP connection");
        text("manualHost", "bad host"); text("gatewayPort", "1"); text("tlsPort", "1"); click("manualSelect");
        check.accept(!label("manualError").isEmpty(), "Journey: manual host validation is shown inline");
        text("manualHost", server.host()); text("gatewayPort", Integer.toString(server.gatewayPort())); text("tlsPort", Integer.toString(server.tlsPort())); click("manualSelect");
        await(() -> has("registerLink"), this::loginLayout);
    }
    private void loginLayout() throws Exception {
        check.accept(scenes.currentScreen() == Screen.LOGIN && context.selectedServer().orElseThrow().gatewayPort() > 0, "Journey: manual selection preserves ServerInfo and opens login"); save("login.png");
        minimum("login-minimum.png", "submitButton", this::keyboard);
    }
    private void keyboard() {
        TextField name = field("username"); name.requestFocus();
        name.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "\t", "\t", javafx.scene.input.KeyCode.TAB, false, false, false, false));
        later(() -> {
            check.accept(stage.getScene().getFocusOwner() != null && stage.getScene().getFocusOwner() != name, "Journey: keyboard Tab navigation reaches the next form control");
            loginValidation();
        });
    }
    private void loginValidation() throws Exception {
        text("username", "bad name"); password("password", "test-only"); click("submitButton");
        check.accept(label("formError").contains("Username"), "Journey: login validates protocol usernames before invoking services");
        click("registerLink"); await(() -> has("confirmation"), this::registration);
    }
    private void registration() throws Exception {
        save("registration.png"); minimum("registration-minimum.png", "submitButton", this::registrationValidation);
    }
    private void registrationValidation() throws Exception {
        text("username", "alice"); password("password", "test-only"); password("confirmation", "different"); click("submitButton");
        check.accept(label("formError").contains("do not match"), "Journey: registration requires matching password confirmation");
        auth.failureCode = "USER_EXISTS"; password("confirmation", "test-only"); click("submitButton");
        await(() -> label("formError").contains("already exists"), this::registrationFailure);
    }
    private void registrationFailure() throws Exception {
        check.accept(scenes.currentScreen() == Screen.REGISTER && !service.state().snapshot().authenticated(), "Journey: USER_EXISTS remains on registration without authentication");
        save("registration-error.png"); auth.failureCode = null; auth.registrationGate = new CountDownLatch(1);
        baseline = auth.registrations.get(); password("password", "test-only"); password("confirmation", "test-only"); click("submitButton"); click("submitButton");
        await(() -> auth.registrations.get() > baseline, this::registrationPending);
    }
    private void registrationPending() throws Exception {
        check.accept(auth.registrations.get() == baseline + 1 && button("submitButton").isDisabled(), "Journey: duplicate registration is prevented while the service is pending");
        save("registration-loading.png"); auth.registrationGate.countDown();
        await(() -> scenes.currentScreen() == Screen.LOGIN && has("registerLink"), this::registered);
    }
    private void registered() throws Exception {
        check.accept(!service.state().snapshot().authenticated(), "Journey: successful registration returns to login without assuming sign-in");
        check.accept(field("username").getText().equals("alice"), "Journey: registration carries only the username back to login");
        auth.failureCode = "BAD_CREDENTIALS"; password("password", "test-only"); click("submitButton");
        await(() -> label("formError").contains("not accepted"), this::loginFailure);
    }
    private void loginFailure() throws Exception {
        check.accept(scenes.currentScreen() == Screen.LOGIN && !service.state().snapshot().authenticated(), "Journey: failed login cannot open the lobby");
        save("login-error.png"); auth.failureCode = null; auth.loginGate = new CountDownLatch(1); scenario.welcomeGate = new CountDownLatch(1);
        baseline = auth.logins.get(); password("password", "test-only"); click("submitButton"); click("submitButton");
        await(() -> auth.logins.get() > baseline, this::loginPending);
    }
    private void loginPending() throws Exception {
        check.accept(auth.logins.get() == baseline + 1 && button("submitButton").isDisabled(), "Journey: login is asynchronous and rejects duplicate submissions");
        check.accept(!auth.callingThread.equals("JavaFX Application Thread"), "Journey: secure login runs on a worker thread");
        save("login-loading.png"); int hellos = scenario.hellos.get(); auth.loginGate.countDown();
        await(() -> scenario.hellos.get() > hellos && service.state().snapshot().connected(), this::tcpPending);
    }
    private void tcpPending() throws Exception {
        check.accept(scenes.currentScreen() == Screen.LOGIN && !service.state().snapshot().authenticated(), "Journey: TLS token receipt does not navigate before TCP WELCOME");
        save("tcp-authenticating.png"); scenario.welcomeGate.countDown();
        await(() -> scenes.currentScreen() == Screen.LOBBY && has("join-r1") && !hasVisible("roomProgress"), this::lobby);
    }
    private void lobby() throws Exception {
        check.accept(status().equals("Connected") && service.state().snapshot().username().equals("alice"), "Journey: real HELLO/WELCOME drives authenticated lobby navigation");
        check.accept(service.state().snapshot().availableRooms().size() == 4, "Journey: actual TCP ROOMS produces all room cards"); save("lobby.png");
        minimum("lobby-minimum.png", "createBattle", this::lobbyFilters);
    }
    private void lobbyFilters() throws Exception {
        baseline = scenario.roomRequests.get(); text("searchRooms", "NETWORK");
        check.accept(((FlowPane) scenes.root().lookup("#roomCards")).getChildren().size() == 1, "Journey: room search filters locally");
        text("searchRooms", ""); filter("RUNNING"); check.accept(button("join-r3").isDisabled(), "Journey: running rooms cannot be joined");
        filter("FINISHED"); check.accept(button("join-r4").isDisabled(), "Journey: finished rooms cannot be joined"); filter("All rooms");
        check.accept(scenario.roomRequests.get() == baseline, "Journey: local search and filters send no additional room requests");
        click("refreshRooms"); await(() -> scenario.roomRequests.get() > baseline && !hasVisible("roomProgress"), this::joinError);
    }
    private void joinError() throws Exception {
        check.accept(scenario.roomRequests.get() == baseline + 1, "Journey: refresh issues one explicit LIST_ROOMS request");
        scenario.nextJoinError = "ROOM_FULL"; click("join-r1");
        await(() -> label("lobbyError").contains("full"), this::createForm);
    }
    private void createForm() throws Exception {
        check.accept(scenes.currentScreen() == Screen.LOBBY && service.state().snapshot().pendingAction() == null, "Journey: ROOM_FULL clears pending join and preserves the lobby");
        click("createBattle"); await(() -> has("createRoomButton"), this::createValidation);
    }
    private void createValidation() throws Exception {
        text("roomName", "bad|name"); click("createRoomButton"); check.accept(!label("createError").isEmpty(), "Journey: room names are validated before sending");
        text("roomName", "Taken"); text("questionCount", "5"); click("createRoomButton");
        await(() -> label("createError").contains("rejected"), this::createRejected);
    }
    private void createRejected() throws Exception {
        check.accept(field("roomName").getText().equals("Taken") && field("questionCount").getText().equals("5"), "Journey: server creation errors preserve form input");
        save("create-error.png"); scenes.notifications().dismiss(); text("roomName", "Campus Champions"); save("create-room.png");
        minimum("create-minimum.png", "createRoomButton", this::createRequest);
    }
    private void createRequest() throws Exception {
        scenario.joinedGate = new CountDownLatch(1); baseline = scenario.creates.get(); click("createRoomButton");
        await(() -> scenario.creates.get() > baseline, this::createPending);
    }
    private void createPending() throws Exception {
        check.accept(scenes.currentScreen() == Screen.CREATE_ROOM && service.state().snapshot().roomId() == null, "Journey: creation waits for JOINED instead of navigating optimistically");
        check.accept(button("createRoomButton").isDisabled(), "Journey: pending creation disables repeated requests");
        save("create-loading.png"); scenario.joinedGate.countDown();
        await(() -> scenes.currentScreen() == Screen.WAITING_ROOM && has("player-bob") && !has("player-charlie"), this::hostRoom);
    }
    private void hostRoom() throws Exception {
        check.accept(button("startBattle").isVisible() && !button("startBattle").isDisabled(), "Journey: only confirmed host status enables Start Battle");
        check.accept(label("rosterNotice").contains("incomplete"), "Journey: the waiting room labels its incomplete roster honestly");
        check.accept(!has("player-charlie"), "Journey: PLAYER_JOINED and PLAYER_LEFT update the actual player cards"); save("waiting-host.png");
        stage.setWidth(960); stage.setHeight(640); later(this::hostMinimum);
    }
    private void hostMinimum() throws Exception {
        save("waiting-minimum.png"); check.accept(button("startBattle").localToScene(button("startBattle").getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Journey: waiting-room controls fit the minimum window width");
        stage.setWidth(wideWidth); stage.setHeight(wideHeight); scenario.questionGate = new CountDownLatch(1); baseline = scenario.starts.get(); click("startBattle");
        await(() -> scenario.starts.get() > baseline, this::startPending);
    }
    private void startPending() throws Exception {
        check.accept(scenes.currentScreen() == Screen.WAITING_ROOM && service.state().snapshot().question() == null, "Journey: host start waits for the server's first QUESTION");
        save("waiting-starting.png"); scenario.questionGate.countDown();
        await(() -> scenes.currentScreen() == Screen.QUIZ && has("leavePlaceholder"), this::firstQuestion);
    }
    private void firstQuestion() throws Exception {
        check.accept(service.state().snapshot().question().id().equals("q1") && service.state().snapshot().question().options().get(1).equals("TCP"), "Journey: the complete TCP question survives navigation to the quiz arena");
        check.accept(scenario.answers.get() == 0, "Journey: reaching the arena does not submit an answer"); save("quiz-initial.png");
        click("leavePlaceholder");
        javafx.stage.Window dialog = javafx.stage.Window.getWindows().stream().filter(w -> w != stage && w.isShowing()).findFirst().orElseThrow();
        ((Button) dialog.getScene().lookup("#confirmLeave")).fire();
        await(() -> scenes.currentScreen() == Screen.LOBBY && has("join-r1") && !hasVisible("roomProgress"), this::guestJoin);
    }
    private void guestJoin() throws Exception {
        check.accept(service.state().snapshot().roomId() == null && service.state().snapshot().authenticated(), "Journey: LEAVE returns locally to the lobby without inventing an acknowledgement");
        click("join-r1"); await(() -> scenes.currentScreen() == Screen.WAITING_ROOM && has("player-bob"), this::guestRoom);
    }
    private void guestRoom() throws Exception {
        check.accept(!service.state().snapshot().host() && !button("startBattle").isVisible(), "Journey: joining as a non-host hides host controls"); save("waiting-guest.png");
        java.util.concurrent.CompletableFuture.runAsync(() -> { try { scenario.firstQuestion(); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); } });
        await(() -> scenes.currentScreen() == Screen.QUIZ, () -> { click("logoutPlaceholder"); await(() -> scenes.currentScreen() == Screen.LOGIN && !service.state().snapshot().authenticated(), this::loggedOut); });
    }
    private void loggedOut() throws Exception {
        check.accept(service.state().snapshot().roomId() == null && service.state().snapshot().question() == null && service.state().snapshot().username() == null, "Journey: logout clears stale room, question and authenticated identity");
        check.accept(new String(auth.suppliedPassword).chars().allMatch(c -> c == 0), "Journey: temporary authentication password arrays have been cleared");
        finder.response = List.of(); click("changeServerButton"); await(() -> !hasVisible("scanProgress") && label("discoveryStatus").contains("No servers"), this::emptyDiscovery);
    }
    private void emptyDiscovery() throws Exception {
        save("discovery-empty.png"); check.accept(!field("manualHost").isDisabled(), "Journey: empty discovery leaves manual selection available");
        finder.fail = true; click("scanButton"); await(() -> !hasVisible("scanProgress") && label("discoveryStatus").contains("failed"), this::failedDiscovery);
    }
    private void failedDiscovery() throws Exception {
        save("discovery-error.png"); check.accept(!button("manualSelect").isDisabled(), "Journey: failed discovery still allows manual connection");
        finder.fail = false; finder.response = null; finder.gate = new CountDownLatch(1); baseline = finder.requests.get(); click("scanButton");
        await(() -> finder.requests.get() > baseline, () -> { click("backWelcome"); finder.gate.countDown(); later(this::done); });
    }
    private void done() throws Exception {
        check.accept(scenes.currentScreen() == Screen.WELCOME, "Journey: late discovery results cannot reopen a disposed screen");
        check.accept(!service.state().snapshot().authenticated(), "Journey: no stale asynchronous result reauthenticates after logout");
        close();
        try (UiContext unavailable = new UiContext(null, "SERVICES NOT CONFIGURED")) {
            unavailable.reducedMotionProperty().set(true);
            SceneManager normal = new SceneManager(stage, unavailable);
            try {
                Scene scene = new Scene(normal.root(), 1280, 800); scene.getStylesheets().addAll(SceneManager.stylesheets()); stage.setScene(scene);
                normal.navigate(Screen.SERVER_SELECTION); normal.root().applyCss(); normal.root().layout();
                check.accept(((Button) normal.root().lookup("#scanButton")).isDisabled()
                        && ((Label) normal.root().lookup("#discoveryStatus")).getText().contains("not configured"), "Journey: unavailable real providers show a useful configuration error without mock fallback");
                unavailable.selectServer(server); normal.navigate(Screen.LOGIN); normal.root().applyCss(); normal.root().layout();
                check.accept(((Button) normal.root().lookup("#submitButton")).isDisabled() && !unavailable.state().snapshot().connected(), "Journey: normal mode never authenticates without a real login provider");
                capture.save(normal.root(), "services-not-configured.png");
            } finally { normal.close(); }
        }
        finished.run();
    }

    private void filter(String value) { @SuppressWarnings("unchecked") ComboBox<String> combo = (ComboBox<String>) scenes.root().lookup("#roomFilter"); combo.setValue(value); layout(); }
    private void text(String id, String value) { field(id).setText(value); layout(); }
    private void password(String id, String value) { ((PasswordInput) scenes.root().lookup("#" + id)).setText(value); }
    private TextField field(String id) { return (TextField) scenes.root().lookup("#" + id); }
    private Button button(String id) { return (Button) scenes.root().lookup("#" + id); }
    private String label(String id) { var label = (Label) scenes.root().lookup("#" + id); return label == null ? "" : label.getText(); }
    private boolean has(String id) { return scenes.root().lookup("#" + id) != null; }
    private boolean hasVisible(String id) { var node = scenes.root().lookup("#" + id); return node != null && node.isVisible(); }
    private String status() { return ((StatusIndicator) scenes.root().lookup("#connectionStatus")).text(); }
    private void click(String id) { layout(); Button button = button(id); if (button == null) { throw new AssertionError("Missing button: " + id); } button.fire(); layout(); }
    private void layout() { scenes.root().applyCss(); scenes.root().layout(); }
    private void save(String name) throws Exception { capture.save(scenes.root(), name); }
    private void minimum(String name, String action, Step next) {
        stage.setWidth(960); stage.setHeight(640);
        later(() -> {
            save(name); checkBounds(action, name);
            var scroll = (javafx.scene.control.ScrollPane) scenes.root().lookup(".page-scroll");
            scroll.setVvalue(1); layout();
            check.accept(button(action).localToScene(button(action).getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Journey: " + action + " remains reachable by scrolling at minimum size");
            stage.setWidth(wideWidth); stage.setHeight(wideHeight); scroll.setVvalue(0); later(next);
        });
    }
    private void checkBounds(String action, String name) {
        check.accept(button(action).localToScene(button(action).getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Journey: " + name + " has no horizontal action clipping");
    }
    private void await(BooleanSupplier condition, Step next) { poll(condition, next, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)); }
    private void poll(BooleanSupplier condition, Step next, long deadline) {
        if (closed) { return; }
        run(() -> {
            layout(); if (condition.getAsBoolean()) { next.run(); return; }
            if (System.nanoTime() > deadline) { throw new AssertionError("Journey timed out at " + scenes.currentScreen() + ": " + service.state().snapshot().notice() + " / " + scenes.notifications().message()); }
            pulse = new PauseTransition(javafx.util.Duration.millis(40)); pulse.setOnFinished(event -> poll(condition, next, deadline)); pulse.play();
        });
    }
    private void later(Step step) { pulse = new PauseTransition(javafx.util.Duration.millis(220)); pulse.setOnFinished(event -> run(step)); pulse.play(); }
    private void run(Step step) { try { step.run(); } catch (Throwable error) { close(); failure.accept(error); } }
    @Override public void close() {
        if (closed) { return; } closed = true; if (pulse != null) { pulse.stop(); } scenes.close();
        for (CountDownLatch gate : new CountDownLatch[] {auth.loginGate, auth.registrationGate, scenario.welcomeGate, scenario.joinedGate, scenario.questionGate, finder.gate}) { if (gate != null) { gate.countDown(); } }
    }
}
