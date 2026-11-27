package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.ConnectionPresentation;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.components.BrandMark;
import com.quizbattle.client.ui.components.StatusIndicator;
import com.quizbattle.common.ServerInfo;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/** Opt-in graphical smoke run; deliberately not named *Test or run by Surefire. */
public final class GuiSmokeLauncher {
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static final List<String> checks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Path OUTPUT = Path.of("m2-tcp-client", "target", "gui-smoke");
    private static GameClientService service;
    private static ServerInfo info;
    private static FakeSecureLoginClient auth;
    private static FakeServerFinder finder;
    private static GuiMockScenario scenario;
    private static UiContext smokeContext;

    private GuiSmokeLauncher() { }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        var watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gui-smoke-watchdog"); thread.setDaemon(true); return thread;
        });
        auth = new FakeSecureLoginClient();
        try (GuiMockScenario script = new GuiMockScenario(auth, false);
             FakeGameServer server = new FakeGameServer(script::play, Integer.MAX_VALUE);
             GameClientService client = new GameClientService(
                     finder = new FakeServerFinder(List.of(new ServerInfo("MOCK", "localhost", server.port(), server.port()))),
                     auth, () -> { var feed = new FakeLiveFeedReceiver(); script.feed = feed; return feed; })) {
            scenario = script;
            service = client; info = new ServerInfo("MOCK", "localhost", server.port(), server.port());
            watchdog.schedule(() -> {
                failure.compareAndSet(null, new AssertionError("Graphical smoke run timed out.")); Platform.runLater(Platform::exit);
            }, 180, TimeUnit.SECONDS);
            Application.launch(SmokeWindow.class, args);
            client.close();
            check(client.awaitTermination(Duration.ofSeconds(3)), "Application workers terminate after window close");
            check(smokeContext.awaitTermination(Duration.ofSeconds(3)), "GUI action and cleanup workers terminate after window close");
        } finally { watchdog.shutdownNow(); }
        Files.writeString(OUTPUT.resolve("smoke-report.txt"), String.join(System.lineSeparator(), checks)
                + System.lineSeparator() + (failure.get() == null ? "PASS" : "FAIL: " + failure.get()));
        if (failure.get() != null) { throw new IllegalStateException("Graphical smoke run failed.", failure.get()); }
        System.out.println("GUI SMOKE PASS · screenshots: " + OUTPUT.toAbsolutePath());
    }

    private static void check(boolean passed, String description) {
        if (!passed) { throw new AssertionError(description); }
        checks.add(description); System.out.println("PASS: " + description);
    }

    public static final class SmokeWindow extends Application {
        private UiContext context;
        private SceneManager scenes;
        private Stage stage;
        private PauseTransition pulse;
        private GuiJourneySmoke journey;
        private GuiGameplaySmoke gameplay;
        private GuiResponsiveSmoke responsive;

        @Override public void start(Stage stage) throws Exception {
            this.stage = stage; context = new UiContext(service, "MOCK MODE · GUI SMOKE"); smokeContext = context;
            context.reducedMotionProperty().set(true);
            scenes = new SceneManager(stage, context);
            Scene scene = new Scene(scenes.root(), 1280, 800); scene.getStylesheets().addAll(SceneManager.stylesheets());
            stage.setTitle("Quiz Battle · GUI smoke test"); stage.getIcons().add(BrandMark.icon());
            stage.setMinWidth(960); stage.setMinHeight(640); stage.setScene(scene); stage.show();
            afterPulse(this::welcome);
        }

        private void welcome() throws Exception {
            check(scenes.currentScreen() == Screen.WELCOME, "Welcome controller initializes");
            check(((StatusIndicator) scenes.root().lookup("#connectionStatus")).text().equals("Disconnected"), "Mock mode never claims a connection before TCP authentication");
            capture("welcome-1280.png");
            ((Button) scenes.root().lookup("#howToPlay")).fire();
            check(javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).count() == 2, "How to Play opens a useful owned dialog");
            javafx.stage.Window guide = javafx.stage.Window.getWindows().stream().filter(window -> window != stage && window.isShowing()).findFirst().orElseThrow();
            capture(guide.getScene().getRoot(), "how-to-play.png");
            scenes.closeHelp();
            ((Button) scenes.root().lookup("#findBattle")).fire();
            check(scenes.currentScreen() == Screen.SERVER_SELECTION, "Find a Battle navigates to server selection");
            capture("server-selection.png"); scenes.back();
            ((Button) scenes.root().lookup("#settingsButton")).fire();
            scenes.root().applyCss(); scenes.root().layout();
            check(scenes.currentScreen() == Screen.SETTINGS, "Settings controller initializes");
            CheckBox motion = (CheckBox) scenes.root().lookup("#reducedMotion");
            motion.fire(); check(!context.reducedMotion(), "Reduced motion control changes the shared preference");
            motion.fire(); check(context.reducedMotion(), "Reduced motion can be restored without lingering animations");
            CheckBox fullscreen = (CheckBox) scenes.root().lookup("#fullscreen");
            fullscreen.fire();
            check(stage.isFullScreen(), "Fullscreen setting enters fullscreen");
            fullscreen.fire(); check(!stage.isFullScreen(), "Fullscreen setting returns to windowed mode");
            capture("settings.png"); scenes.back();
            stage.setWidth(960); stage.setHeight(640); afterPulse(this::minimumSize);
        }

        private void minimumSize() throws Exception {
            capture("welcome-minimum.png");
            Node find = scenes.root().lookup("#findBattle"); Node help = scenes.root().lookup("#howToPlay");
            check(find.localToScene(find.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Primary action fits the minimum window width");
            check(help.localToScene(help.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Secondary action fits the minimum window width");
            check(find.localToScene(find.getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Primary action stays visible at the minimum window height");
            scenes.perform(() -> CompletableFuture.failedFuture(new java.io.IOException("private upstream content")),
                    "Testing asynchronous feedback", "The test request failed safely.");
            check(scenes.loading().isVisible(), "Loading overlay appears during asynchronous operations");
            capture("loading.png"); afterPulse(this::notifications);
        }

        private void notifications() throws Exception {
            check(!scenes.loading().isVisible(), "Loading overlay clears after failure");
            check(scenes.notifications().isVisible() && scenes.notifications().message().equals("The test request failed safely."),
                    "Async exceptions show a safe error notification on the FX thread");
            capture("error-notification.png"); scenes.notifications().dismiss();
            service.connect(info, FakeSecureLoginClient.TOKEN).whenComplete((value, error) -> onUi(() -> {
                if (error != null) { throw new AssertionError("Mock TCP authentication failed", error); }
                check(Platform.isFxApplicationThread(), "Background service state is delivered on the FX Application Thread");
                check(((StatusIndicator) scenes.root().lookup("#connectionStatus")).text().equals("Connected"), "Connection indicator follows actual TCP WELCOME");
                check(scenes.currentScreen() == Screen.LOBBY, "Authenticated application state selects its protected route");
                scenes.navigate(Screen.QUIZ);
                check(!scenes.root().lookup("#headerActions").isVisible(), "Quiz route uses a minimal header");
                service.disconnect().whenComplete((ignored, disconnectError) -> onUi(this::disconnected));
            }));
        }

        private void disconnected() throws Exception {
            check(scenes.currentScreen() == Screen.WELCOME, "Disconnect removes access to protected routes");
            check(ConnectionPresentation.from(service.state().snapshot()).text().equals("Disconnected"), "Disconnect presentation returns to the actual disconnected state");
            scenes.navigate(Screen.FINAL_RESULTS);
            check(scenes.currentScreen() == Screen.LOGIN, "Unauthenticated final-results navigation is guarded");
            context.reducedMotionProperty().set(false); scenes.navigate(Screen.WELCOME);
            Node page = ((javafx.scene.layout.StackPane) scenes.root().lookup("#contentHost")).getChildren().get(0);
            check(page.getOpacity() < 1, "Page navigation starts its short fade transition");
            afterPulse(this::hoverMotion);
        }

        private void hoverMotion() throws Exception {
            Node page = ((javafx.scene.layout.StackPane) scenes.root().lookup("#contentHost")).getChildren().get(0);
            check(page.getOpacity() == 1 && page.getTranslateY() == 0, "Page transition settles without delaying interaction");
            Node find = scenes.root().lookup("#findBattle");
            find.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
                    0, 0, 0, 0, javafx.scene.input.MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false, null));
            afterPulse(this::dispose);
        }

        private void dispose() throws Exception {
            Node find = scenes.root().lookup("#findBattle");
            check(find.getScaleX() > 1, "Button hover applies its subtle scale transition");
            context.reducedMotionProperty().set(true);
            check(find.getScaleX() == 1 && find.getScaleY() == 1, "Reduced motion resets an active hover effect");
            scenes.close();
            String before = ((StatusIndicator) scenes.root().lookup("#connectionStatus")).text();
            service.state().disconnected("Game server connection lost. After UI disposal.");
            afterPulse(() -> {
                check(((StatusIndicator) scenes.root().lookup("#connectionStatus")).text().equals(before), "Disposed scenes stop observing model changes");
                journey = new GuiJourneySmoke(stage, context, service, scenario, auth, finder, info, GuiSmokeLauncher::check,
                        this::capture, problem -> { failure.compareAndSet(null, problem); problem.printStackTrace(); stage.close(); Platform.exit(); },
                        () -> {
                            try {
                                gameplay = new GuiGameplaySmoke(stage, context, service, scenario, info, GuiSmokeLauncher::check, this::capture,
                                        problem -> { failure.compareAndSet(null, problem); problem.printStackTrace(); stage.close(); Platform.exit(); },
                                        () -> {
                                            try {
                                                responsive = new GuiResponsiveSmoke(stage,context,service,scenario,info,GuiSmokeLauncher::check,this::capture,
                                                        problem -> { failure.compareAndSet(null,problem);problem.printStackTrace();stage.close();Platform.exit(); },
                                                        () -> {stage.close();Platform.exit();});
                                                responsive.start();
                                            } catch(Exception problem){failure.compareAndSet(null,problem);stage.close();Platform.exit();}
                                        });
                                gameplay.start();
                            } catch (Exception problem) { failure.compareAndSet(null, problem); stage.close(); Platform.exit(); }
                        });
                journey.start();
            });
        }

        @FunctionalInterface private interface Step { void run() throws Exception; }
        private void onUi(Step step) { Platform.runLater(() -> run(step)); }
        private void afterPulse(Step step) {
            // This is a GUI-only wait for a layout/animation pulse, never a socket-test sleep.
            pulse = new PauseTransition(javafx.util.Duration.millis(220));
            pulse.setOnFinished(event -> run(step)); pulse.play();
        }
        private void run(Step step) {
            try { step.run(); }
            catch (Throwable problem) { failure.compareAndSet(null, problem); problem.printStackTrace(); stage.close(); Platform.exit(); }
        }
        private void capture(String name) throws Exception {
            capture(scenes.root(), name);
        }
        private void capture(javafx.scene.Parent root, String name) throws Exception {
            root.applyCss(); root.layout();
            WritableImage image = root.snapshot(null, null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) {
                for (int x = 0; x < png.getWidth(); x++) { png.setRGB(x, y, image.getPixelReader().getArgb(x, y)); }
            }
            Files.createDirectories(OUTPUT.resolve(name).getParent());
            ImageIO.write(png, "png", OUTPUT.resolve(name).toFile());
        }
        @Override public void stop() {
            if (responsive != null) { responsive.close(); }
            if (gameplay != null) { gameplay.close(); }
            if (journey != null) { journey.close(); }
            if (pulse != null) { pulse.stop(); }
            if (scenes != null) { scenes.close(); }
            if (context != null) { context.close(); }
        }
    }
}
