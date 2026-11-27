package com.quizbattle.client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class LoadingOverlay extends StackPane {
    private final Label message = new Label("Working...");
    public LoadingOverlay() {
        getStyleClass().add("loading-overlay");
        ProgressIndicator progress = new ProgressIndicator(); progress.setMaxSize(38, 38);
        message.getStyleClass().add("card-title");
        VBox card = new VBox(16, progress, message); card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("loading-card"); card.setMaxSize(300, 160);
        getChildren().add(card); managedProperty().bind(visibleProperty()); setVisible(false);
    }
    public void show(String text) { message.setText(text); setVisible(true); }
    public void hide() { setVisible(false); }
}
