package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.ui.AsyncViewScope;
import com.quizbattle.client.ui.FormValidation;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.Screen;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiErrors;
import com.quizbattle.client.ui.components.PasswordInput;
import com.quizbattle.client.ui.components.ToastNotification;
import java.time.Duration;
import java.util.Arrays;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Shared credential handling; credentials never enter snapshots or preferences. */
abstract class AuthController implements ViewController {
    protected final UiContext context; protected final SceneManager scenes;
    protected AsyncViewScope tasks;
    @FXML protected TextField username;
    @FXML protected PasswordInput password;
    @FXML protected PasswordInput confirmation;
    @FXML protected Label selectedServer;
    @FXML protected Label formError;
    @FXML protected Label authStatus;
    @FXML protected Label securityNote;
    @FXML protected VBox authProgress;
    @FXML protected VBox formFields;
    @FXML protected Button submitButton;
    private boolean loginStarted;
    private boolean welcomed;
    protected AuthController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    protected void configure() {
        username.setText(context.suggestedUsername());
        selectedServer.setText(context.selectedServer().map(server -> server.name() + "\n" + server.host() + " · TCP " + server.gatewayPort() + " · TLS " + server.tlsPort())
                .orElse("Choose a server before signing in."));
        authProgress.managedProperty().bind(authProgress.visibleProperty());
        tasks = new AsyncViewScope(Platform::runLater, busy -> {
            formFields.setDisable(busy); submitButton.setDisable(busy || context.service().isEmpty() || context.selectedServer().isEmpty()); authProgress.setVisible(busy);
        });
        submitButton.setDisable(context.service().isEmpty() || context.selectedServer().isEmpty());
        if (context.service().isEmpty()) { formError.setText("Secure login services are not configured. Ask your team for an integrated client build."); }
        securityNote.setText(context.mode().contains("MOCK") ? "MOCK MODE: authentication and live feed are test fakes. The test game session uses actual TCP."
                : "Credentials use the TLS authentication channel. Game messages use a separate, unencrypted TCP connection.");
    }
    protected void authenticate(boolean registration) {
        if (tasks.busy()) { return; }
        char[] secret = password.copyPassword(); char[] repeated = confirmation == null ? null : confirmation.copyPassword();
        try {
            String name = FormValidation.username(username.getText());
            if (registration) { FormValidation.registration(name, secret, repeated); } else { FormValidation.password(secret); }
            var server = context.selectedServer().orElseThrow(() -> new IllegalArgumentException("Select a server first."));
            if (context.service().isEmpty()) { throw new IllegalArgumentException("Network services are not configured."); }
            formError.setText(""); password.clear(); if (confirmation != null) { confirmation.clear(); }
            authStatus.setText(context.mode().contains("MOCK") ? (registration ? "Mock account registration…" : "Mock authentication…")
                    : (registration ? "Creating your account securely…" : "Secure authentication…"));
            loginStarted = !registration; welcomed = false;
            if (!registration) { context.beginAuthentication(); }
            char[] owned = secret.clone();
            tasks.run(() -> context.invoke(() -> {
                try { return registration ? context.requireService().register(server, name, owned) : context.requireService().login(server, name, owned); }
                finally { Arrays.fill(owned, '\0'); }
            }).whenComplete((value, error) -> Arrays.fill(owned, '\0')), Duration.ofSeconds(60), ignored -> {
                if (registration) {
                    context.suggestUsername(name); scenes.navigate(Screen.LOGIN);
                    scenes.notifications().show("Account created. Sign in to connect to the game.", ToastNotification.Tone.SUCCESS);
                }
                // Login navigation comes only from the authoritative WELCOME snapshot.
            }, failure -> {
                if (!registration) { context.disconnect(); }
                formError.setText(UiErrors.message(failure, registration ? "Registration failed. Check the server and try again." : "Sign-in failed. Check your credentials and server settings."));
            });
        } catch (IllegalArgumentException e) { formError.setText(e.getMessage()); }
        finally { Arrays.fill(secret, '\0'); if (repeated != null) { Arrays.fill(repeated, '\0'); } }
    }
    @Override public void update(Snapshot state) {
        if (!loginStarted || !tasks.busy()) { return; }
        if (state.authenticated()) { welcomed = true; }
        else if (state.status() == GameStatus.CONNECTING) { authStatus.setText("Connecting to game server…"); }
        else if (state.status() == GameStatus.AUTHENTICATING) { authStatus.setText(state.connected() ? "Authenticating game session…"
                : context.mode().contains("MOCK") ? "Mock authentication…" : "Secure authentication…"); }
    }
    @FXML protected void changeServer() { scenes.navigate(Screen.SERVER_SELECTION); }
    @Override public void close() {
        tasks.close(); password.clear(); if (confirmation != null) { confirmation.clear(); }
        if (loginStarted && !welcomed) { context.disconnect(); }
    }
}
