package com.quizbattle.client.ui;

import javafx.animation.ScaleTransition;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/** Small, disposable hover motion shared by interactive controls. */
public final class UiMotion implements AutoCloseable {
    private final Node node;
    private final UiContext context;
    private final ScaleTransition scale;
    private final EventHandler<MouseEvent> enter;
    private final EventHandler<MouseEvent> exit;
    private final ChangeListener<Boolean> preference;

    public UiMotion(Node node, UiContext context) {
        this.node = node; this.context = context;
        scale = new ScaleTransition(Duration.millis(140), node);
        enter = event -> animate(1.025); exit = event -> animate(1);
        preference = (value, old, reduced) -> { if (reduced) { reset(); } };
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, enter);
        node.addEventHandler(MouseEvent.MOUSE_EXITED, exit);
        context.reducedMotionProperty().addListener(preference);
    }
    private void animate(double target) {
        if (context.reducedMotion() || node.isDisabled()) { return; }
        scale.stop(); scale.setToX(target); scale.setToY(target); scale.playFromStart();
    }
    private void reset() { scale.stop(); node.setScaleX(1); node.setScaleY(1); }
    @Override public void close() {
        reset(); node.removeEventHandler(MouseEvent.MOUSE_ENTERED, enter);
        node.removeEventHandler(MouseEvent.MOUSE_EXITED, exit);
        context.reducedMotionProperty().removeListener(preference);
    }
}
