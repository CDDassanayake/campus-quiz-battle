package com.quizbattle.client.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.util.Duration;

/** Brief count-up to a server value; interpolation never writes to game state. */
public final class ScoreMotion implements AutoCloseable {
    private final Label label;
    private final UiContext context;
    private final DoubleProperty value = new SimpleDoubleProperty();
    private final ChangeListener<Boolean> reduced;
    private Timeline animation;
    private int target;
    public ScoreMotion(Label label, UiContext context) {
        this.label = label; this.context = context;
        value.addListener((p, old, number) -> label.setText("+" + Math.round(number.doubleValue()) + " pts"));
        reduced = (p, old, active) -> { if (active) { settle(); } };
        context.reducedMotionProperty().addListener(reduced);
    }
    public void show(int serverPoints) {
        target = serverPoints; settle();
        if (!context.reducedMotion() && target > 0) {
            value.set(0); animation = new Timeline(new KeyFrame(Duration.millis(260), new KeyValue(value, target))); animation.play();
        }
    }
    private void settle() { if (animation != null) { animation.stop(); animation = null; } value.set(target); label.setText("+" + target + " pts"); }
    @Override public void close() { settle(); context.reducedMotionProperty().removeListener(reduced); }
}
