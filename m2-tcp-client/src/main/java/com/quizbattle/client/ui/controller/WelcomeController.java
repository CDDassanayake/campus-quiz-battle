package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiMotion;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

public final class WelcomeController implements ViewController {
    private final SceneManager scenes;
    private final UiContext context;
    private final List<UiMotion> motions = new ArrayList<>();
    @FXML private Button findBattle;
    @FXML private Button howToPlay;
    public WelcomeController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        motions.add(new UiMotion(findBattle, context)); motions.add(new UiMotion(howToPlay, context));
    }
    @FXML private void find() { scenes.navigate(Screen.SERVER_SELECTION); }
    @FXML private void help() { scenes.help(); }
    @Override public void close() { motions.forEach(UiMotion::close); motions.clear(); }
}
