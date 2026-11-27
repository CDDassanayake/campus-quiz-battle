package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.ui.RoomReply;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.components.BattleCards;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class WaitingRoomController extends RoomActionController {
    @FXML private Label roomTitle;
    @FXML private Label roomDetails;
    @FXML private Label rosterNotice;
    @FXML private Label waitingError;
    @FXML private VBox playerCards;
    @FXML private VBox nonHostPanel;
    @FXML private ProgressIndicator waitingIndicator;
    @FXML private HBox startProgress;
    @FXML private Button startBattle;
    @FXML private Button leaveRoom;
    private Snapshot snapshot;
    public WaitingRoomController(UiContext context, SceneManager scenes) { super(context, scenes); }
    @FXML private void initialize() {
        startBattle.managedProperty().bind(startBattle.visibleProperty()); nonHostPanel.managedProperty().bind(nonHostPanel.visibleProperty());
        startProgress.managedProperty().bind(startProgress.visibleProperty());
        waitingIndicator.visibleProperty().bind(context.reducedMotionProperty().not());
        waitingIndicator.managedProperty().bind(waitingIndicator.visibleProperty());
        configureActions(busy -> { startProgress.setVisible(busy); render(); });
    }
    @Override public void update(Snapshot state) { super.update(state); snapshot = state; render(); }
    private void render() {
        if (snapshot == null) { return; }
        roomTitle.setText(snapshot.roomName());
        roomDetails.setText("ROOM " + snapshot.roomId() + "  ·  " + (snapshot.host() ? "You are the host" : "Waiting for the host"));
        rosterNotice.setText(snapshot.knownPlayers().size() + " player identities known to this client. The initial roster may be incomplete.");
        playerCards.getChildren().clear();
        snapshot.knownPlayers().stream().sorted().forEach(name -> playerCards.getChildren().add(BattleCards.player(name,
                name.equals(snapshot.username()), name.equals(snapshot.username()) && snapshot.host())));
        boolean pending = tasks.busy() || snapshot.pendingAction() != null;
        startBattle.setVisible(snapshot.host()); startBattle.setDisable(pending || snapshot.status() != GameStatus.WAITING_ROOM);
        nonHostPanel.setVisible(!snapshot.host()); leaveRoom.setDisable(false);
    }
    @FXML private void start() {
        waitingError.setText(""); awaitReply(RoomReply.Kind.QUESTION, () -> context.requireService().startGame(), waitingError::setText);
    }
    @FXML private void leave() {
        scenes.perform(() -> context.invoke(() -> context.requireService().leaveRoom()), "Leaving room…", "Could not leave the room. Try disconnecting.");
    }
    @Override public void close() { super.close(); waitingIndicator.visibleProperty().unbind(); }
}
