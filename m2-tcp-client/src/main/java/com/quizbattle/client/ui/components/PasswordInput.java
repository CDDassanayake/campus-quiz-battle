package com.quizbattle.client.ui.components;

import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Reusable visibility toggle. clear() releases both JavaFX field values. */
public final class PasswordInput extends VBox {
    private final PasswordField masked = new PasswordField();
    private final TextField revealed = new TextField();
    private final CheckBox visible = new CheckBox("Show password");
    public PasswordInput() {
        super(8); revealed.textProperty().bindBidirectional(masked.textProperty());
        revealed.visibleProperty().bind(visible.selectedProperty());
        revealed.managedProperty().bind(revealed.visibleProperty());
        masked.visibleProperty().bind(visible.selectedProperty().not());
        masked.managedProperty().bind(masked.visibleProperty());
        visible.getStyleClass().add("password-toggle");
        getChildren().addAll(new StackPane(masked, revealed), visible);
        setPromptText("Password");
    }
    public String getPromptText() { return masked.getPromptText(); }
    public void setPromptText(String text) { masked.setPromptText(text); revealed.setPromptText(text); masked.setAccessibleText(text); revealed.setAccessibleText(text); }
    public char[] copyPassword() { return masked.getText().toCharArray(); }
    public void clear() { masked.clear(); revealed.clear(); visible.setSelected(false); }
    /** Useful for explicit test launchers; application code reads passwords only on submit. */
    public void setText(String value) { masked.setText(value); }
}
