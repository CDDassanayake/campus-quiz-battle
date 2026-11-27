package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.FormValidation;
import com.quizbattle.client.ui.RoomReply;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

public final class CreateRoomController extends RoomActionController {
    @FXML private TextField roomName;
    @FXML private TextField questionCount;
    @FXML private Label roomPreview;
    @FXML private Label createError;
    @FXML private Button createRoomButton;
    @FXML private Button cancelCreate;
    @FXML private HBox createProgress;
    private boolean serverPending;
    public CreateRoomController(UiContext context, SceneManager scenes) { super(context, scenes); }
    @FXML private void initialize() {
        questionCount.setText("5"); roomName.textProperty().addListener((value, old, text) -> preview());
        questionCount.textProperty().addListener((value, old, text) -> preview());
        createProgress.managedProperty().bind(createProgress.visibleProperty());
        configureActions(busy -> {
            createProgress.setVisible(busy); roomName.setDisable(busy); questionCount.setDisable(busy);
            createRoomButton.setDisable(busy || serverPending);
            cancelCreate.setDisable(busy);
        }); preview();
    }
    private void preview() {
        roomPreview.setText((roomName.getText().isEmpty() ? "Your battle" : roomName.getText()) + "\n" + questionCount.getText() + " questions requested · you become host after confirmation");
        createError.setText("");
    }
    @FXML private void create() {
        if (tasks.busy()) { return; }
        try {
            String name = FormValidation.roomName(roomName.getText()); int count = FormValidation.questionCount(questionCount.getText());
            createError.setText(""); awaitReply(RoomReply.Kind.JOINED, () -> context.requireService().createRoom(name, count), createError::setText);
        } catch (IllegalArgumentException e) { createError.setText(e.getMessage()); }
    }
    @Override public void update(Snapshot snapshot) {
        super.update(snapshot); serverPending = snapshot.pendingAction() != null;
        createRoomButton.setDisable(tasks.busy() || serverPending);
    }
    @FXML private void cancel() { scenes.navigate(Screen.LOBBY); }
}
