package com.quizbattle.client.ui.components;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.PlayerScore;
import com.quizbattle.client.ui.RankingPresentation;
import com.quizbattle.client.ui.UiContext;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.util.List;
import java.util.Objects;

public final class LeaderboardView extends VBox implements AutoCloseable {
    private final Label freshness = new Label();
    private final ListView<PlayerScore> rows = new ListView<>();
    private final UiContext context;
    private final Timeline age;
    private final javafx.beans.value.ChangeListener<Boolean> reduced;
    private FadeTransition motion;
    private Snapshot snapshot;
    private List<PlayerScore> rendered;
    private String username;
    private boolean authoritative;
    public LeaderboardView(UiContext context, boolean authoritative) {
        this.context = context; this.authoritative = authoritative; setSpacing(12);
        getStyleClass().addAll("content-card", "ranking-panel"); setMinWidth(0);
        Label heading = new Label(authoritative ? "Complete standings" : "Live rankings"); heading.getStyleClass().add("section-title");
        freshness.getStyleClass().add("supporting-text"); freshness.setWrapText(true); freshness.setId("rankingFreshness");
        Label rule = new Label(authoritative ? "Server listing order. Equal scores do not define a client tie-break." : "Informational UDP feed. Equal scores use username order.");
        rule.setWrapText(true); rule.getStyleClass().add("supporting-text");
        getChildren().addAll(heading, freshness, rows, rule);
        rows.setId("rankingList"); rows.getStyleClass().add("ranking-list"); rows.setFixedCellSize(60);
        rows.setMinWidth(0); rows.setMinHeight(62); rows.setMaxHeight(362);
        Label empty = new Label(authoritative ? "No standings were supplied." : "Waiting for live rankings…");
        empty.setWrapText(true); rows.setPlaceholder(empty);
        rows.setAccessibleText(authoritative ? "Final server standings" : "Informational live rankings");
        rows.setCellFactory(list -> new RankingCell());
        age = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshAge())); age.setCycleCount(Timeline.INDEFINITE);
        if (!authoritative) { age.play(); }
        reduced = (p, old, value) -> { if (value) { if (motion != null) { motion.stop(); } rows.setOpacity(1); } };
        context.reducedMotionProperty().addListener(reduced);
    }
    public void update(Snapshot value) {
        snapshot = value; refreshAge();
        List<PlayerScore> values = authoritative && value.finalResult() != null ? value.finalResult().standings() : value.leaderboard();
        if (values.equals(rendered) && Objects.equals(username, value.username())) { return; }
        rendered = values; username = value.username(); rows.getItems().setAll(values);
        rows.setPrefHeight(Math.max(1, Math.min(6, values.size())) * 60 + 2);
        rows.refresh();
        if (motion != null) { motion.stop(); } rows.setOpacity(1);
        if (!context.reducedMotion()) { motion = new FadeTransition(Duration.millis(140), rows); motion.setFromValue(.65); motion.setToValue(1); motion.play(); }
    }
    /** Reuses visible rows rather than building thousands of nodes on every feed update. */
    private final class RankingCell extends ListCell<PlayerScore> {
        private final Label rank = new Label();
        private final Label name = new Label();
        private final Label score = new Label();
        private final HBox row = new HBox(12, rank, name, score);
        RankingCell() {
            setMinWidth(0); setPrefWidth(0); row.getStyleClass().add("ranking-row");
            name.setWrapText(true); name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(name, Priority.ALWAYS);
            score.getStyleClass().add("ranking-score");
            indexProperty().addListener((p, old, index) -> updateRank());
        }
        private void updateRank() {
            rank.setText(String.format("%02d", getIndex() + 1)); rank.getStyleClass().setAll(getIndex() == 0 ? "rank-gold" : "rank-number");
        }
        @Override protected void updateItem(PlayerScore player, boolean empty) {
            super.updateItem(player, empty);
            if (empty || player == null) { setGraphic(null); setAccessibleText(null); return; }
            updateRank(); boolean self = player.username().equals(username);
            name.setText(player.username() + (self ? "  · YOU" : "")); score.setText(Integer.toString(player.score()));
            row.getStyleClass().remove("ranking-self"); if (self) { row.getStyleClass().add("ranking-self"); }
            setAccessibleText("Position " + (getIndex() + 1) + ", " + player.username() + (self ? ", you" : "") + ", " + player.score() + " points");
            setGraphic(row);
        }
    }
    private void refreshAge() {
        if (snapshot == null) { return; }
        freshness.setText(authoritative ? "Final · authoritative TCP result" : RankingPresentation.freshness(snapshot.leaderboardRevision(), snapshot.leaderboardReceivedNanos(), System.nanoTime()));
    }
    @Override public void close() { age.stop(); if (motion != null) { motion.stop(); } context.reducedMotionProperty().removeListener(reduced); }
}
