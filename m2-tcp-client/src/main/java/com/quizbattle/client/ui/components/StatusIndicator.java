package com.quizbattle.client.ui.components;

import com.quizbattle.client.ui.ConnectionPresentation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

public final class StatusIndicator extends HBox {
    private final Label text = new Label("Disconnected");
    public StatusIndicator() {
        super(8); setAlignment(Pos.CENTER_LEFT);
        getStyleClass().addAll("status-indicator", "status-disconnected");
        Circle dot = new Circle(4); dot.getStyleClass().add("status-dot");
        text.getStyleClass().add("status-text"); getChildren().addAll(dot, text);
    }
    public void update(ConnectionPresentation presentation) {
        getStyleClass().removeAll("status-disconnected", "status-pending", "status-connected", "status-lost");
        getStyleClass().add(presentation.styleClass()); text.setText(presentation.text());
        setAccessibleText(presentation.text());
    }
    public String text() { return text.getText(); }
}
