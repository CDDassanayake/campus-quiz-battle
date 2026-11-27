package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.UiContext;

/** Honest route foundation: it performs no discovery, authentication or gameplay. */
public final class PlaceholderController implements ViewController {
    private final SceneManager scenes;
    private final UiContext context;
    @FXML private Label title;
    @FXML private Label description;
    @FXML private Label nextStep;
    @FXML private Label receivedQuestion;
    @FXML private Button leavePlaceholder;
    @FXML private Button logoutPlaceholder;
    public PlaceholderController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    public void route(Screen screen) {
        title.setText(screen.title());
        description.setText("Your session is connected and the server's question is retained. Interactive quiz answers, round results and final rankings arrive in Phase 6. The console remains the full gameplay interface.");
        nextStep.setText("PHASE 6 PLACEHOLDER  /  " + screen.name().replace('_', ' '));
    }
    @Override public void update(Snapshot state) {
        receivedQuestion.setText(state.question() == null ? "" : "Received QUESTION " + state.question().id() + " · " + state.question().number() + " of " + state.question().total());
        leavePlaceholder.setVisible(state.roomId() != null); leavePlaceholder.setManaged(state.roomId() != null);
        logoutPlaceholder.setVisible(state.authenticated()); logoutPlaceholder.setManaged(state.authenticated());
    }
    @FXML private void back() { scenes.back(); }
    @FXML private void home() { scenes.home(); }
    @FXML private void leave() { scenes.perform(() -> context.invoke(() -> context.requireService().leaveRoom()), "Leaving room…", "Could not leave the room."); }
    @FXML private void logout() { scenes.logout(); }
}
