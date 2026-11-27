package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.ConnectionPresentation;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.components.StatusIndicator;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;

public final class ShellController {
    private final UiContext context;
    private final SceneManager scenes;
    @FXML private StackPane contentHost;
    @FXML private StatusIndicator connectionStatus;
    @FXML private Label playerLabel;
    @FXML private Label footerStatus;
    @FXML private Label modeLabel;
    @FXML private Label pageTitle;
    @FXML private HBox headerActions;
    @FXML private Button disconnectButton;
    @FXML private BorderPane shellRoot;
    @FXML private Button headerHelp;
    private Screen currentRoute = Screen.WELCOME;
    private boolean connected;
    public ShellController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        footerStatus.setTooltip(new javafx.scene.control.Tooltip());
        modeLabel.setText(context.mode());
        disconnectButton.managedProperty().bind(disconnectButton.visibleProperty());
        playerLabel.managedProperty().bind(playerLabel.visibleProperty());
        pageTitle.visibleProperty().bind(shellRoot.widthProperty().greaterThan(1120)); pageTitle.managedProperty().bind(pageTitle.visibleProperty());
        headerHelp.visibleProperty().bind(shellRoot.widthProperty().greaterThan(1160)); headerHelp.managedProperty().bind(headerHelp.visibleProperty());
    }
    public StackPane contentHost() { return contentHost; }
    public void update(Snapshot snapshot) {
        connectionStatus.update(ConnectionPresentation.from(snapshot));
        playerLabel.setText(snapshot.username() == null ? "" : snapshot.username());
        playerLabel.setVisible(snapshot.authenticated()); connected = snapshot.connected(); updateDisconnect();
        footerStatus.setText(snapshot.serverHost() == null ? snapshot.notice()
                : snapshot.serverHost() + ":" + snapshot.serverPort() + "  ·  " + snapshot.notice());
        footerStatus.setMinWidth(0);
        footerStatus.getTooltip().setText(footerStatus.getText());
    }
    public void route(Screen screen) {
        currentRoute = screen; updateDisconnect();
        pageTitle.setText(screen == Screen.WELCOME ? "CAMPUS EDITION" : screen.title());
        headerActions.setVisible(screen != Screen.QUIZ); headerActions.setManaged(screen != Screen.QUIZ);
    }
    private void updateDisconnect() {
        disconnectButton.setVisible(connected && currentRoute != Screen.QUIZ && currentRoute != Screen.ROUND_RESULT && currentRoute != Screen.FINAL_RESULTS);
    }
    @FXML private void home() { scenes.home(); }
    @FXML private void help() { scenes.help(); }
    @FXML private void settings() { scenes.navigate(Screen.SETTINGS); }
    @FXML private void disconnect() {
        scenes.logout();
    }
}
