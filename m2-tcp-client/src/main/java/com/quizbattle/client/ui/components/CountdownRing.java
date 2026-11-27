package com.quizbattle.client.ui.components;

import com.quizbattle.client.ui.UiContext;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** Animates only actual reported values. Never ticks locally or decides answer eligibility. */
public final class CountdownRing extends StackPane implements AutoCloseable {
    private final Arc ring = new Arc(0, 0, 39, 39, 90, -360);
    private final Label seconds = new Label();
    private final UiContext context;
    private final ChangeListener<Boolean> reduced;
    private Timeline animation;
    private int previous = -1;
    private double target;
    public CountdownRing(UiContext context) {
        this.context = context; setMinSize(94, 94); setPrefSize(94, 94); setMaxSize(94, 94);
        Circle track = new Circle(39); track.getStyleClass().add("timer-track");
        ring.setType(ArcType.OPEN); ring.getStyleClass().add("timer-ring");
        // Arc bounds change with its length; unmanaged shapes retain a stable center.
        track.setManaged(false); track.setCenterX(47); track.setCenterY(47);
        ring.setManaged(false); ring.setCenterX(47); ring.setCenterY(47);
        seconds.getStyleClass().add("timer-number"); seconds.setId("countdownSeconds");
        getChildren().addAll(track, ring, seconds);
        reduced = (p, old, value) -> { if (value) { stop(); ring.setLength(target); } };
        context.reducedMotionProperty().addListener(reduced);
    }
    public void update(int remaining, int limit, boolean reset) {
        if (!reset && previous == remaining) { return; } previous = remaining;
        stop(); seconds.setText(Integer.toString(remaining));
        ring.getStyleClass().removeAll("timer-warning", "timer-danger");
        if (remaining <= 3) { ring.getStyleClass().add("timer-danger"); }
        else if (remaining <= 5) { ring.getStyleClass().add("timer-warning"); }
        target = -360.0 * Math.max(0, Math.min(remaining, limit)) / Math.max(1, limit);
        if (reset || context.reducedMotion()) { ring.setLength(target); }
        else { animation = new Timeline(new KeyFrame(Duration.millis(180), new KeyValue(ring.lengthProperty(), target))); animation.play(); }
    }
    private void stop() { if (animation != null) { animation.stop(); animation = null; } }
    @Override public void close() { stop(); context.reducedMotionProperty().removeListener(reduced); }
}
