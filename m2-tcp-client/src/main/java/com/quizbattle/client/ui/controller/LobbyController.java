package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.RoomInfo;
import com.quizbattle.client.ui.RoomPresentation;
import com.quizbattle.client.ui.RoomReply;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiErrors;
import com.quizbattle.client.ui.components.BattleCards;
import java.time.Duration;
import java.util.List;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

public final class LobbyController extends RoomActionController {
    @FXML private Label greeting;
    @FXML private Label serverInfo;
    @FXML private Label lobbyError;
    @FXML private Label emptyRooms;
    @FXML private TextField searchRooms;
    @FXML private ComboBox<String> roomFilter;
    @FXML private FlowPane roomCards;
    @FXML private Button refreshRooms;
    @FXML private Button createBattle;
    @FXML private HBox roomProgress;
    private Snapshot snapshot;
    public LobbyController(UiContext context, SceneManager scenes) { super(context, scenes); }
    @FXML private void initialize() {
        roomFilter.getItems().setAll("All rooms", "LOBBY", "RUNNING", "FINISHED"); roomFilter.setValue("All rooms");
        searchRooms.textProperty().addListener((value, old, text) -> render()); roomFilter.valueProperty().addListener((value, old, text) -> render());
        roomProgress.managedProperty().bind(roomProgress.visibleProperty());
        configureActions(busy -> { roomProgress.setVisible(busy); render(); });
    }
    @Override public void shown() { refresh(); }
    @Override public void update(Snapshot state) { super.update(state); snapshot = state; render(); }
    private void render() {
        if (snapshot == null || tasks == null) { return; }
        greeting.setText("Ready for your next battle, " + snapshot.username() + "?");
        serverInfo.setText(snapshot.serverHost() + " · TCP " + snapshot.serverPort());
        boolean pending = tasks.busy() || snapshot.pendingAction() != null;
        roomProgress.setVisible(pending);
        createBattle.setDisable(pending); refreshRooms.setDisable(pending);
        List<RoomInfo> shown = RoomPresentation.filter(snapshot.availableRooms(), searchRooms.getText(), roomFilter.getValue());
        roomCards.getChildren().clear();
        for (RoomInfo room : shown) { roomCards.getChildren().add(BattleCards.room(room, pending || !RoomPresentation.joinable(room), () -> join(room))); }
        emptyRooms.setVisible(shown.isEmpty()); emptyRooms.setManaged(shown.isEmpty());
        emptyRooms.setText(snapshot.availableRooms().isEmpty() ? "No rooms yet. Create the first battle, or refresh when your friends are ready."
                : "No rooms match your search. Try another name, ID or room state.");
    }
    @FXML private void refresh() {
        if (tasks.busy()) { return; }
        lobbyError.setText("");
        tasks.run(() -> context.invoke(() -> context.requireService().refreshRooms()), Duration.ofSeconds(8), ignored -> render(),
                failure -> lobbyError.setText(UiErrors.message(failure, "Rooms could not be refreshed. Check the connection.")));
    }
    @FXML private void create() { scenes.navigate(Screen.CREATE_ROOM); }
    private void join(RoomInfo room) {
        lobbyError.setText("");
        awaitReply(RoomReply.Kind.JOINED, () -> context.requireService().joinRoom(room.id()), lobbyError::setText);
    }
    @FXML private void logout() { scenes.logout(); }
}
