package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.AsyncViewScope;
import com.quizbattle.client.ui.FormValidation;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiErrors;
import com.quizbattle.client.ui.components.BattleCards;
import com.quizbattle.common.ServerInfo;
import java.time.Duration;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;

public final class ServerSelectionController implements ViewController {
    private final UiContext context; private final SceneManager scenes;
    private AsyncViewScope tasks;
    @FXML private FlowPane servers;
    @FXML private Button scanButton;
    @FXML private ProgressIndicator scanProgress;
    @FXML private Label discoveryStatus;
    @FXML private Label manualError;
    @FXML private TextField manualHost;
    @FXML private TextField gatewayPort;
    @FXML private TextField tlsPort;
    public ServerSelectionController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        manualHost.setText(context.selectedServer().map(ServerInfo::host).orElse(context.defaultHost()));
        gatewayPort.setText(Integer.toString(context.selectedServer().map(ServerInfo::gatewayPort).orElse(context.defaultGatewayPort())));
        tlsPort.setText(Integer.toString(context.selectedServer().map(ServerInfo::tlsPort).orElse(context.defaultTlsPort())));
        scanProgress.managedProperty().bind(scanProgress.visibleProperty());
        tasks = new AsyncViewScope(Platform::runLater, busy -> { scanButton.setDisable(busy); scanProgress.setVisible(busy); });
        if (context.service().isEmpty()) {
            scanButton.setDisable(true);
            discoveryStatus.setText("Network services are not configured in this build. Ask your team to supply the real discovery and secure login services. Manual selection remains available.");
        }
    }
    @Override public void shown() { if (context.service().isPresent()) { scan(); } }
    @FXML private void scan() {
        if (tasks.busy() || context.service().isEmpty()) { return; }
        discoveryStatus.setText("Scanning your local network… You can also enter a server manually.");
        tasks.run(context::discoverServers, Duration.ofSeconds(4), found -> {
            servers.getChildren().clear();
            for (int i = 0; i < found.size(); i++) { ServerInfo server = found.get(i); servers.getChildren().add(BattleCards.server(server, i, () -> choose(server))); }
            discoveryStatus.setText(found.isEmpty() ? "No servers answered. Check the network, refresh, or enter the server address manually."
                    : found.size() + " server(s) discovered. Select where you want to play.");
        }, failure -> discoveryStatus.setText(UiErrors.message(failure, "Discovery is unavailable. Retry or select a server manually.")));
    }
    private void choose(ServerInfo server) {
        try { context.selectServer(server); scenes.navigate(Screen.LOGIN); }
        catch (IllegalArgumentException e) { manualError.setText(e.getMessage()); }
    }
    @FXML private void manual() {
        try { choose(FormValidation.server(manualHost.getText(), gatewayPort.getText(), tlsPort.getText())); }
        catch (IllegalArgumentException e) { manualError.setText(e.getMessage()); }
    }
    @FXML private void back() { scenes.navigate(Screen.WELCOME); }
    @Override public void close() { tasks.close(); }
}
