package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.FinalResult;
import com.quizbattle.client.ui.*;
import com.quizbattle.client.ui.components.*;
import javafx.animation.FadeTransition;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class FinalResultsController implements ViewController {
    private final UiContext context;
    private final SceneManager scenes;
    private LeaderboardView standings;
    private FinalResult rendered;
    private FadeTransition reveal;
    private ChangeListener<Boolean> reduced;
    @FXML private Label winnerName, personalResult;
    @FXML private StackPane podiumHost;
    @FXML private VBox standingsHost;
    public FinalResultsController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        standings = new LeaderboardView(context, true); standingsHost.getChildren().add(standings);
        reduced = (p, old, value) -> { if (value) { stop(); } }; context.reducedMotionProperty().addListener(reduced);
    }
    @Override public void update(Snapshot s) {
        standings.update(s); if (s.finalResult() == null || rendered == s.finalResult()) { return; }
        rendered = s.finalResult(); stop();
        winnerName.setText(rendered.winner().isEmpty() ? "No winner supplied" : rendered.winner() + " wins the battle!");
        personalResult.setText(RankingPresentation.personal(rendered.standings(), s.username()));
        podiumHost.getChildren().setAll(new WinnerPodium(rendered.standings()));
        if (!context.reducedMotion()) { reveal = new FadeTransition(Duration.millis(280), podiumHost); reveal.setFromValue(0); reveal.setToValue(1); reveal.play(); }
    }
    private void stop() { if (reveal != null) { reveal.stop(); reveal = null; } podiumHost.setOpacity(1); }
    @FXML private void lobby() { scenes.perform(() -> context.invoke(() -> context.requireService().leaveRoom()), "Returning to lobby…", "Could not leave the finished room."); }
    @FXML private void logout() { scenes.logout(); }
    @Override public void close() { stop(); standings.close(); context.reducedMotionProperty().removeListener(reduced); }
}
