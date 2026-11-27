package com.quizbattle.client.ui.components;

import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Original geometric bolt, rendered locally at any size. */
public final class BrandMark extends StackPane {
    private final SVGPath bolt = new SVGPath();
    private double size;
    public BrandMark() {
        getStyleClass().add("brand-mark");
        bolt.setContent("M 23 1 L 8 23 L 19 23 L 15 39 L 33 16 L 22 16 Z");
        bolt.getStyleClass().add("brand-bolt"); getChildren().add(bolt); setSize(40);
        setAccessibleText("Quiz Battle lightning bolt");
    }
    public double getSize() { return size; }
    public void setSize(double value) {
        size = value; setMinSize(value, value); setPrefSize(value, value); setMaxSize(value, value);
        bolt.setScaleX(value / 56); bolt.setScaleY(value / 56);
    }
    public static Image icon() {
        BrandMark mark = new BrandMark(); mark.setSize(64);
        Scene scene = new Scene(mark, 64, 64, Color.TRANSPARENT);
        scene.getStylesheets().add(BrandMark.class.getResource("/css/theme.css").toExternalForm());
        scene.getStylesheets().add(BrandMark.class.getResource("/css/components.css").toExternalForm());
        mark.applyCss(); mark.resize(64, 64); mark.layout();
        SnapshotParameters parameters = new SnapshotParameters(); parameters.setFill(Color.TRANSPARENT);
        return mark.snapshot(parameters, null);
    }
}
