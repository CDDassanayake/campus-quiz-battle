package com.quizbattle.client.ui.components;

import com.quizbattle.client.model.RoomInfo;
import com.quizbattle.common.ServerInfo;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Data-driven card content; page arrangement remains in FXML and styling in CSS. */
public final class BattleCards {
    private BattleCards() { }
    private static Label label(String text, String style) {
        Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add(style); return label;
    }
    private static VBox card(String style) {
        VBox card = new VBox(12); card.setPrefWidth(310); card.setMinWidth(240); card.getStyleClass().addAll("content-card", style); return card;
    }
    public static VBox server(ServerInfo server, int index, Runnable select) {
        VBox card = card("server-card"); card.setId("server-card-" + index);
        Button button = new Button("Select server  →"); button.setId("select-server-" + index);
        button.getStyleClass().add("primary-button"); button.setMaxWidth(Double.MAX_VALUE); button.setOnAction(event -> select.run());
        card.getChildren().addAll(label("CAMPUS SERVER", "eyebrow"), label(server.name(), "card-title"),
                label(server.host(), "body-secondary"), label("TCP  " + server.gatewayPort() + "     TLS  " + server.tlsPort(), "supporting-text"), button);
        return card;
    }
    public static VBox room(RoomInfo room, boolean disabled, Runnable join) {
        VBox card = card("room-card"); card.setId("room-card-" + room.id());
        Label state = label(room.status().name(), "room-state"); state.getStyleClass().add("room-state-" + room.status().name().toLowerCase(java.util.Locale.ROOT));
        Button action = new Button("Join battle  →"); action.setId("join-" + room.id()); action.setDisable(disabled);
        action.getStyleClass().add("primary-button"); action.setMaxWidth(Double.MAX_VALUE); action.setOnAction(event -> join.run());
        card.getChildren().addAll(state, label(room.name(), "card-title"), label("Room " + room.id() + "  ·  " + room.playerCount() + " players reported", "body-secondary"), action);
        return card;
    }
    public static HBox player(String name, boolean self, boolean host) {
        Label avatar = label(name.substring(0, Math.min(2, name.length())).toUpperCase(java.util.Locale.ROOT), "player-avatar");
        Label description = label(self ? (host ? "You · room host" : "You") : "Known player", "supporting-text");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox card = new HBox(16, avatar, new VBox(4, label(name, "card-title"), description), spacer, label("Seen in this room", "player-presence"));
        card.setAlignment(Pos.CENTER_LEFT); card.getStyleClass().addAll("content-card", "player-card"); card.setId("player-" + name);
        return card;
    }
}
