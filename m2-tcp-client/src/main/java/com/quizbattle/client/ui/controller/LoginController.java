package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import javafx.fxml.FXML;

public final class LoginController extends AuthController {
    public LoginController(UiContext context, SceneManager scenes) { super(context, scenes); }
    @FXML private void initialize() { configure(); }
    @FXML private void submit() { authenticate(false); }
    @FXML private void register() { scenes.navigate(Screen.REGISTER); }
}
