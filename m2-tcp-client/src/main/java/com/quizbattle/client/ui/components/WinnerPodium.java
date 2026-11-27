package com.quizbattle.client.ui.components;

import com.quizbattle.client.model.PlayerScore;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.util.List;

/** Server order is preserved, including ties. Winner announcement is rendered separately. */
public final class WinnerPodium extends HBox {
    public WinnerPodium(List<PlayerScore> players) {
        setSpacing(18); setAlignment(Pos.BOTTOM_CENTER); setFillHeight(false); setId("winnerPodium");
        int count = Math.min(3, players.size());
        int[] positions = count == 3 ? new int[]{1, 0, 2} : count == 2 ? new int[]{0, 1} : new int[]{0};
        for (int position : positions) {
            if (position >= count) { continue; }
            PlayerScore p = players.get(position);
            Label avatar = new Label(p.username().substring(0, 1).toUpperCase(java.util.Locale.ROOT)); avatar.getStyleClass().add("podium-avatar");
            Label name = new Label(p.username()); name.setWrapText(true); name.setAlignment(Pos.CENTER); name.setMaxWidth(Double.MAX_VALUE);
            Label score = new Label(p.score() + " pts"); score.getStyleClass().add("podium-score");
            Label rank = new Label("SERVER POSITION " + (position + 1)); rank.getStyleClass().add("supporting-text");
            VBox step = new VBox(12, avatar, name, score, rank); step.setAlignment(Pos.CENTER);
            step.getStyleClass().addAll("podium-step", "podium-" + position); step.setMinWidth(0); step.setMaxWidth(250);
            step.setPrefHeight(position == 0 ? 240 : position == 1 ? 210 : 190); HBox.setHgrow(step, Priority.ALWAYS);
            getChildren().add(step);
        }
    }
}
