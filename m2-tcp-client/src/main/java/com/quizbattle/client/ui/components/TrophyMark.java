package com.quizbattle.client.ui.components;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Small local vector; no remote artwork or platform emoji dependency. */
public final class TrophyMark extends StackPane {
    public TrophyMark() {
        SVGPath cup = new SVGPath();
        cup.setContent("M16 6 H48 V23 C48 35 41 41 32 41 C23 41 16 35 16 23 Z M16 12 H5 V22 C5 31 11 34 20 34 M48 12 H59 V22 C59 31 53 34 44 34 M32 41 V53 M21 58 H43 M26 53 H38");
        cup.getStyleClass().add("trophy-vector"); getChildren().add(cup); setMinSize(68, 68); setPrefSize(68, 68); setMaxSize(68, 68);
    }
}
