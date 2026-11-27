package com.quizbattle.client.ui.components;

import com.quizbattle.client.ui.UiContext;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/** One reusable, nonmodal notification with bounded animation ownership. */
public final class ToastNotification extends HBox implements AutoCloseable {
    public enum Tone { INFO, SUCCESS, WARNING, ERROR }
    private final Label text = new Label();
    private final UiContext context;
    private final ChangeListener<Boolean> motionListener;
    private Animation animation;
    public ToastNotification(UiContext context) {
        super(16); this.context = context; setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("toast"); setMaxWidth(560); setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        text.setWrapText(true); text.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
        Button close = new Button("×"); close.getStyleClass().add("icon-button");
        close.setAccessibleText("Dismiss notification"); close.setOnAction(event -> dismiss());
        getChildren().addAll(text, close); managedProperty().bind(visibleProperty()); setVisible(false);
        motionListener = (property, old, reduced) -> {
            if (reduced && isVisible()) { stopAnimation(); setOpacity(1); setTranslateY(0); scheduleDismiss(); }
        };
        context.reducedMotionProperty().addListener(motionListener);
    }
    public void show(String message, Tone tone) {
        stopAnimation(); getStyleClass().removeAll("toast-info", "toast-success", "toast-warning", "toast-error");
        getStyleClass().add("toast-" + tone.name().toLowerCase(java.util.Locale.ROOT));
        text.setText(message); setAccessibleText(message); setVisible(true);
        if (context.reducedMotion()) { setOpacity(1); setTranslateY(0); scheduleDismiss(); return; }
        setOpacity(0); setTranslateY(-8);
        FadeTransition fade = new FadeTransition(Duration.millis(180), this); fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(180), this); move.setToY(0);
        ParallelTransition enter = new ParallelTransition(fade, move);
        enter.setOnFinished(event -> scheduleDismiss()); animation = enter; enter.play();
    }
    private void scheduleDismiss() {
        PauseTransition hold = new PauseTransition(Duration.seconds(5));
        if (context.reducedMotion()) { hold.setOnFinished(event -> dismiss()); animation = hold; hold.play(); return; }
        FadeTransition fade = new FadeTransition(Duration.millis(180), this); fade.setToValue(0);
        SequentialTransition sequence = new SequentialTransition(hold, fade);
        sequence.setOnFinished(event -> dismiss()); animation = sequence; sequence.play();
    }
    private void stopAnimation() { if (animation != null) { animation.stop(); animation = null; } }
    public void dismiss() { stopAnimation(); setVisible(false); setOpacity(1); setTranslateY(0); }
    public String message() { return text.getText(); }
    @Override public void close() { dismiss(); context.reducedMotionProperty().removeListener(motionListener); }
}
