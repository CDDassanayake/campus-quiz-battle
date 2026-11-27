package com.quizbattle.client.ui.components;

import com.quizbattle.client.model.Question;
import com.quizbattle.client.ui.UiContext;
import javafx.animation.ScaleTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import java.util.function.BiConsumer;

/** Reusable keyboard accessible card. A press retains the question it began on. */
public final class AnswerCard extends Button implements AutoCloseable {
    private final Label text = new Label();
    private final int index;
    private final UiContext context;
    private final ChangeListener<Boolean> reduced;
    private final ScaleTransition motion = new ScaleTransition(Duration.millis(110), this);
    private Question question;
    private Question armedQuestion;
    private boolean armed;
    private boolean keyHeld;

    public AnswerCard(int index, UiContext context, BiConsumer<Question, Integer> choose) {
        this.index = index; this.context = context;
        getStyleClass().addAll("answer-card", "answer-" + index);
        setMaxWidth(Double.MAX_VALUE); setMinWidth(0); setMinHeight(100);
        Label badge = new Label("" + "ABCD".charAt(index)); badge.getStyleClass().add("answer-letter");
        badge.setMinWidth(40); text.setWrapText(true); text.setMinWidth(0);
        text.maxWidthProperty().bind(widthProperty().subtract(108));
        text.prefWidthProperty().bind(widthProperty().subtract(108));
        text.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(100, text.prefHeight(Math.max(30, getWidth() - 108)) + 38),
                widthProperty(), text.textProperty(), text.fontProperty()));
        HBox content = new HBox(16, badge, text); content.setAlignment(Pos.CENTER_LEFT); setGraphic(content);
        setId("answer" + index);
        setOnMousePressed(e -> { armedQuestion = question; armed = true; animate(.98); });
        setOnMouseReleased(e -> animate(1));
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if ((e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) && !keyHeld) {
                keyHeld = true; armed = true; armedQuestion = question;
            }
        });
        addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> keyHeld = false);
        setOnAction(e -> {
            Question expected = armed ? armedQuestion : question;
            // Clear only after action: a new QUESTION between press and release must reject the old press.
            if (!keyHeld) { armed = false; } choose.accept(expected, index);
        });
        setOnMouseEntered(e -> animate(1.015)); setOnMouseExited(e -> animate(1));
        reduced = (p, old, value) -> { if (value) { resetMotion(); } };
        context.reducedMotionProperty().addListener(reduced);
    }
    public void question(Question value) {
        question = value; text.setText(value.options().get(index));
        setAccessibleText("Answer " + (index + 1) + ": " + text.getText());
    }
    public void selected(boolean selected) {
        getStyleClass().remove("answer-selected"); if (selected) { getStyleClass().add("answer-selected"); }
    }
    public void outcome(int correct, Integer selected) {
        getStyleClass().removeAll("answer-correct", "answer-incorrect");
        if (index == correct) { getStyleClass().add("answer-correct"); }
        else if (selected != null && index == selected) { getStyleClass().add("answer-incorrect"); }
    }
    private void animate(double scale) {
        if (context.reducedMotion()) { return; }
        motion.stop(); motion.setToX(scale); motion.setToY(scale); motion.play();
    }
    private void resetMotion() { motion.stop(); setScaleX(1); setScaleY(1); }
    @Override public void close() { resetMotion(); context.reducedMotionProperty().removeListener(reduced); }
}
