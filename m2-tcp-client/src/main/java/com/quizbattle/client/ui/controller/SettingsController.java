package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.UiContext;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public final class SettingsController implements ViewController {
    private final UiContext context;
    private final SceneManager scenes;
    private final ChangeListener<Boolean> fullscreenListener;
    @FXML private CheckBox reducedMotion;
    @FXML private CheckBox fullscreen;
    public SettingsController(UiContext context, SceneManager scenes) {
        this.context = context; this.scenes = scenes;
        fullscreenListener = (value, old, active) -> fullscreen.setSelected(active);
    }
    @FXML private void initialize() {
        reducedMotion.selectedProperty().bindBidirectional(context.reducedMotionProperty());
        fullscreen.setSelected(scenes.stage().isFullScreen());
        scenes.stage().fullScreenProperty().addListener(fullscreenListener);
    }
    @FXML private void toggleFullscreen() { scenes.stage().setFullScreen(fullscreen.isSelected()); }
    @FXML private void back() { scenes.back(); }
    @Override public void close() {
        reducedMotion.selectedProperty().unbindBidirectional(context.reducedMotionProperty());
        scenes.stage().fullScreenProperty().removeListener(fullscreenListener);
    }
}
