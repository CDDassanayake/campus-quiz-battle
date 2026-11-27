package com.quizbattle.client.ui;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.components.BrandMark;
import com.quizbattle.common.AppConfig;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

public final class ClientApplication extends Application {
    private record Injection(GameClientService service, String mode) { }
    private static final AtomicReference<Injection> INJECTION = new AtomicReference<>();
    private UiContext context;
    private SceneManager scenes;
    private static volatile UiContext shutdownContext;

    /** Test/app wiring can supply real or explicitly labelled mock services. */
    public static void launchInjected(GameClientService service, String mode, String... args) {
        if (!INJECTION.compareAndSet(null, new Injection(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(mode)))) {
            throw new IllegalStateException("Application dependencies were already supplied.");
        }
        Application.launch(ClientApplication.class, args);
        awaitShutdown();
    }

    @Override public void init() throws Exception {
        Injection injection = INJECTION.getAndSet(null);
        if (injection != null) {
            context = new UiContext(injection.service(), injection.mode(), AppConfig.load("config/client.properties")); return;
        }
        try {
            var provider = ServiceLoader.load(GuiServiceProvider.class).findFirst();
            if (provider.isPresent()) {
                GameClientService service = java.util.Objects.requireNonNull(provider.get().create(AppConfig.load("config/client.properties")));
                context = new UiContext(service, "CAMPUS LAN", AppConfig.load("config/client.properties"));
            } else { context = new UiContext(null, "SERVICES NOT CONFIGURED", AppConfig.load("config/client.properties")); }
        } catch (Exception | ServiceConfigurationError e) {
            context = new UiContext(null, "GUI FOUNDATION · SERVICES UNAVAILABLE");
            context.state().operationFailed("Network services could not be initialized. Check local configuration.");
        }
    }

    @Override public void start(Stage stage) throws Exception {
        scenes = new SceneManager(stage, context);
        Scene scene = new Scene(scenes.root(), 1280, 800); scene.getStylesheets().addAll(SceneManager.stylesheets());
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) { stage.setFullScreen(!stage.isFullScreen()); event.consume(); }
        });
        stage.setTitle("Campus Quiz Battle · Connect. Compete. Conquer."); stage.getIcons().add(BrandMark.icon());
        stage.setMinWidth(960); stage.setMinHeight(640); stage.setScene(scene);
        stage.setFullScreenExitHint("Press Esc to return to windowed mode."); stage.centerOnScreen(); stage.show();
    }
    @Override public void stop() {
        if (scenes != null) { scenes.close(); }
        if (context != null) { context.close(); }
        shutdownContext = context;
    }
    public static void awaitShutdown() {
        UiContext closing = shutdownContext;
        try {
            if (closing != null && !closing.awaitTermination(java.time.Duration.ofSeconds(3))) {
                throw new IllegalStateException("Client cleanup did not finish within the shutdown deadline.");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
