package com.quizbattle.client.ui.controller;

import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import javafx.fxml.FXML;

public final class RegisterController extends AuthController {
    public RegisterController(UiContext context, SceneManager scenes) { super(context, scenes); }
    @FXML private void initialize() { configure(); }
    @FXML private void submit() { authenticate(true); }
    @FXML private void login() { scenes.navigate(Screen.LOGIN); }
}
