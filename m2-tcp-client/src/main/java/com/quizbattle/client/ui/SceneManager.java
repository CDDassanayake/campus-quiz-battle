package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.components.LoadingOverlay;
import com.quizbattle.client.ui.components.ToastNotification;
import com.quizbattle.client.ui.controller.PlaceholderController;
import com.quizbattle.client.ui.controller.SettingsController;
import com.quizbattle.client.ui.controller.ShellController;
import com.quizbattle.client.ui.controller.ViewController;
import com.quizbattle.client.ui.controller.WelcomeController;
import com.quizbattle.client.ui.controller.ServerSelectionController;
import com.quizbattle.client.ui.controller.LoginController;
import com.quizbattle.client.ui.controller.RegisterController;
import com.quizbattle.client.ui.controller.LobbyController;
import com.quizbattle.client.ui.controller.CreateRoomController;
import com.quizbattle.client.ui.controller.WaitingRoomController;
import com.quizbattle.client.ui.controller.QuizController;
import com.quizbattle.client.ui.controller.RoundResultController;
import com.quizbattle.client.ui.controller.FinalResultsController;
import javafx.scene.input.KeyEvent;
import javafx.event.EventHandler;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** One window, one snapshot subscription, explicit disposal of every outgoing view. */
public final class SceneManager implements AutoCloseable {
    private final Stage stage;
    private final UiContext context;
    private final NavigationHistory history = new NavigationHistory();
    private final Parent root;
    private final ShellController shell;
    private final LoadingOverlay loading = new LoadingOverlay();
    private final ToastNotification toast;
    private final UiStateSubscription subscription;
    private final ChangeListener<Boolean> reducedMotion;
    private ViewController view;
    private Parent page;
    private ParallelTransition transition;
    private Dialog<ButtonType> help;
    private Dialog<ButtonType> leaveConfirmation;
    private final AnswerKeys answerKeys = new AnswerKeys();
    private final java.util.Set<String> activationKeys = new java.util.HashSet<>();
    private final EventHandler<KeyEvent> keyPressed = event -> {
        String key = event.getCode().name();
        if ((key.equals("SPACE") || key.equals("ENTER")) && !activationKeys.add(key)) {
            if (view instanceof QuizController) { event.consume(); } return;
        }
        int choice = AnswerKeys.choice(event.getCode().name());
        boolean first = answerKeys.press(event.getCode().name());
        if (choice >= 0 && view instanceof QuizController quiz && gameplayInputAllowed()) {
            if (first && stage().isFocused()) { quiz.shortcut(choice); } event.consume();
        }
    };
    private final EventHandler<KeyEvent> keyReleased = event -> {
        answerKeys.release(event.getCode().name()); activationKeys.remove(event.getCode().name());
    };
    private Snapshot previous;
    private long operation;
    private boolean closed;
    private boolean logoutPending;

    public SceneManager(Stage stage, UiContext context) throws IOException {
        requireUiThread(); this.stage = stage; this.context = context;
        FXMLLoader loader = loader("/fxml/shell.fxml"); root = loader.load(); shell = loader.getController();
        toast = new ToastNotification(context); toast.setId("notificationToast"); loading.setId("loadingOverlay");
        reducedMotion = (property, old, reduced) -> { if (reduced) { stopTransition(); } };
        context.reducedMotionProperty().addListener(reducedMotion);
        display(Screen.WELCOME);
        root.addEventFilter(KeyEvent.KEY_PRESSED, keyPressed);
        root.addEventFilter(KeyEvent.KEY_RELEASED, keyReleased);
        subscription = new UiStateSubscription(context.state(), Platform::runLater, this::apply, true);
    }

    public static URL resource(String path) { return Objects.requireNonNull(SceneManager.class.getResource(path), "Missing application resource: " + path); }
    public static List<String> stylesheets() { return List.of(resource("/css/theme.css").toExternalForm(), resource("/css/components.css").toExternalForm(), resource("/css/gameplay.css").toExternalForm()); }
    public Parent root() { return root; }
    public Stage stage() { return stage; }
    public Screen currentScreen() { return history.current(); }
    public LoadingOverlay loading() { return loading; }
    public ToastNotification notifications() { return toast; }

    private FXMLLoader loader(String path) {
        FXMLLoader loader = new FXMLLoader(resource(path));
        loader.setControllerFactory(type -> {
            if (type == ShellController.class) { return new ShellController(context, this); }
            if (type == WelcomeController.class) { return new WelcomeController(context, this); }
            if (type == PlaceholderController.class) { return new PlaceholderController(context, this); }
            if (type == SettingsController.class) { return new SettingsController(context, this); }
            if (type == ServerSelectionController.class) { return new ServerSelectionController(context, this); }
            if (type == LoginController.class) { return new LoginController(context, this); }
            if (type == RegisterController.class) { return new RegisterController(context, this); }
            if (type == LobbyController.class) { return new LobbyController(context, this); }
            if (type == CreateRoomController.class) { return new CreateRoomController(context, this); }
            if (type == WaitingRoomController.class) { return new WaitingRoomController(context, this); }
            if (type == QuizController.class) { return new QuizController(context, this); }
            if (type == RoundResultController.class) { return new RoundResultController(context, this); }
            if (type == FinalResultsController.class) { return new FinalResultsController(context, this); }
            throw new IllegalArgumentException("Unsupported view controller.");
        });
        return loader;
    }

    public void navigate(Screen requested) {
        requireUiThread(); if (closed) { return; }
        boolean authenticated = context.state().snapshot().authenticated();
        Screen old = history.current(); Screen target = history.navigate(requested, authenticated);
        if (target != old) { displaySafely(target); }
        if (requested.requiresAuthentication() && !authenticated) {
            toast.show("Sign in before opening a player screen. Select a server if you have not chosen one yet.", ToastNotification.Tone.INFO);
        }
    }
    public void back() {
        requireUiThread(); if (closed) { return; }
        var state = context.state().snapshot();
        if (state.authenticated()) { navigate(activeScreen(state)); }
        else { displaySafely(history.back(false)); }
    }

    private void displaySafely(Screen target) {
        try { display(target); }
        catch (IOException | RuntimeException e) {
            toast.show("This screen could not be loaded. Return home or restart the application.", ToastNotification.Tone.ERROR);
        }
    }
    private void display(Screen screen) throws IOException {
        FXMLLoader loader = loader(screen.resource()); Parent nextPage = loader.load();
        ViewController nextView = loader.getController();
        if (nextView instanceof PlaceholderController placeholder) { placeholder.route(screen); }
        stopTransition(); closeLeaveConfirmation(); if (view != null) { view.close(); }
        operation++; loading.hide(); toast.dismiss(); view = nextView; page = nextPage;
        shell.contentHost().getChildren().setAll(page, loading, toast);
        StackPane.setAlignment(toast, Pos.TOP_RIGHT); StackPane.setMargin(toast, new Insets(24));
        shell.route(screen); view.update(context.state().snapshot()); view.shown();
        if (!context.reducedMotion()) {
            page.setOpacity(0); page.setTranslateY(6);
            FadeTransition fade = new FadeTransition(Duration.millis(180), page); fade.setToValue(1);
            TranslateTransition move = new TranslateTransition(Duration.millis(180), page); move.setToY(0);
            transition = new ParallelTransition(fade, move); transition.play();
        }
    }
    private void stopTransition() {
        if (transition != null) { transition.stop(); transition = null; }
        if (page != null) { page.setOpacity(1); page.setTranslateY(0); }
    }

    private void apply(Snapshot snapshot) {
        requireUiThread(); if (closed) { return; }
        Snapshot old = previous; previous = snapshot; shell.update(snapshot); view.update(snapshot);
        if (!snapshot.authenticated() && history.current().requiresAuthentication()) {
            navigate(context.selectedServer().isPresent() ? Screen.LOGIN : Screen.WELCOME);
        }
        else if (snapshot.authenticated() && !context.logoutRequested() && (old == null || old.status() != snapshot.status())) {
            Screen target = activeScreen(snapshot);
            if (target != null && history.current() != Screen.SETTINGS) { navigate(target); }
        }
        if (snapshot.errorCode() != null && (old == null || !Objects.equals(snapshot.errorCode(), old.errorCode())
                || !Objects.equals(snapshot.notice(), old.notice()))) {
            toast.show(snapshot.notice(), ToastNotification.Tone.ERROR);
        } else if (old != null && !Objects.equals(ConnectionPresentation.from(old), ConnectionPresentation.from(snapshot))
                && "status-lost".equals(ConnectionPresentation.from(snapshot).styleClass())) {
            toast.show("Connection lost. Reconnect from the client when the server is available.", ToastNotification.Tone.ERROR);
        }
    }

    private static Screen activeScreen(Snapshot state) {
        return switch (state.status()) {
            case WAITING_ROOM -> Screen.WAITING_ROOM;
            case QUESTION_ACTIVE, ANSWER_SUBMITTED -> Screen.QUIZ;
            case ROUND_RESULT -> Screen.ROUND_RESULT;
            case GAME_OVER -> Screen.FINAL_RESULTS;
            default -> Screen.LOBBY;
        };
    }
    public void home() {
        navigate(context.state().snapshot().authenticated() && !context.logoutRequested() ? activeScreen(context.state().snapshot()) : Screen.WELCOME);
    }
    public void logout() {
        requireUiThread(); if (logoutPending || closed) { return; }
        logoutPending = true;
        perform(() -> context.disconnect().whenComplete((value, failure) -> Platform.runLater(() -> {
            logoutPending = false;
            if (!closed && failure == null) { navigate(context.selectedServer().isPresent() ? Screen.LOGIN : Screen.WELCOME); }
        })), "Disconnecting…", "Could not close the connection. Try again.");
    }

    /** Reusable asynchronous controller action; no future joins on the FX thread. */
    public void perform(Supplier<? extends CompletableFuture<?>> request, String description, String safeFailure) {
        requireUiThread(); if (closed) { return; }
        long requestId = ++operation; loading.show(description);
        try {
            request.get().whenComplete((value, failure) -> Platform.runLater(() -> {
                if (closed || requestId != operation) { return; }
                loading.hide(); if (failure != null) { toast.show(safeFailure, ToastNotification.Tone.ERROR); }
            }));
        } catch (RuntimeException e) { loading.hide(); toast.show(safeFailure, ToastNotification.Tone.ERROR); }
    }

    public void help() {
        requireUiThread();
        if (help != null && help.isShowing()) { help.getDialogPane().getScene().getWindow().requestFocus(); return; }
        try {
            help = new Dialog<>(); help.initOwner(stage); help.setTitle("How to play · Quiz Battle");
            ScrollPane guide = new FXMLLoader(resource("/fxml/help.fxml")).load();
            guide.setPrefViewportHeight(Math.min(560, Math.max(300, stage.getHeight() - 220)));
            help.getDialogPane().setContent(guide);
            help.getDialogPane().getStylesheets().addAll(stylesheets());
            help.getDialogPane().getStyleClass().add("quiz-dialog");
            help.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            modalKeys(help);
            help.getDialogPane().lookupButton(ButtonType.CLOSE).getStyleClass().add("primary-button"); help.show();
        } catch (IOException e) { toast.show("Help could not be opened.", ToastNotification.Tone.ERROR); }
    }

    public void closeHelp() { if (help != null) { help.close(); help = null; } }
    public boolean gameplayInputAllowed() {
        return !closed && !loading.isVisible() && (help == null || !help.isShowing())
                && (leaveConfirmation == null || !leaveConfirmation.isShowing());
    }
    /** Modal presentation uses show(), so network snapshots continue to be applied. */
    public void confirmLeave() {
        requireUiThread(); if (closed || leaveConfirmation != null || context.state().snapshot().roomId() == null) { return; }
        String room = context.state().snapshot().roomId();
        Dialog<ButtonType> dialog = new Dialog<>(); leaveConfirmation = dialog;
        dialog.initOwner(stage); dialog.setTitle("Leave this battle?");
        dialog.setContentText("Leave the active room and return to the lobby?");
        dialog.getDialogPane().getStylesheets().addAll(stylesheets());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        modalKeys(dialog);
        dialog.getDialogPane().lookupButton(ButtonType.OK).setId("confirmLeave");
        dialog.setOnHidden(e -> {
            if (leaveConfirmation != dialog) { return; } leaveConfirmation = null;
            if (dialog.getResult() == ButtonType.OK && Objects.equals(room, context.state().snapshot().roomId())) {
                perform(() -> context.invoke(() -> context.requireService().leaveRoom()), "Leaving room…", "Could not leave the room.");
            }
        }); dialog.show();
    }
    public void closeLeaveConfirmation() {
        if (leaveConfirmation != null) { Dialog<ButtonType> old = leaveConfirmation; leaveConfirmation = null; old.close(); }
    }
    private void modalKeys(Dialog<?> dialog) {
        // Remember presses/releases delivered to the owned dialog too, so a held
        // answer key cannot become a fresh shortcut immediately after it closes.
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, keyPressed);
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_RELEASED, keyReleased);
    }
    private static void requireUiThread() {
        if (!Platform.isFxApplicationThread()) { throw new IllegalStateException("Presentation operations require the JavaFX Application Thread."); }
    }
    @Override public void close() {
        requireUiThread(); if (closed) { return; }
        closed = true; subscription.close(); stopTransition(); view.close(); closeHelp(); closeLeaveConfirmation(); toast.close(); loading.hide();
        root.removeEventFilter(KeyEvent.KEY_PRESSED, keyPressed); root.removeEventFilter(KeyEvent.KEY_RELEASED, keyReleased);
        context.reducedMotionProperty().removeListener(reducedMotion);
    }
}
