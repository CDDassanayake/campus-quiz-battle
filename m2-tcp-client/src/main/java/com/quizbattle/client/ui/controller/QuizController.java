package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.Question;
import com.quizbattle.client.ui.*;
import com.quizbattle.client.ui.components.*;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.Animation;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class QuizController implements ViewController {
    private final UiContext context;
    private final SceneManager scenes;
    private final List<AnswerCard> cards = new ArrayList<>();
    private CountdownRing timer;
    private LeaderboardView leaderboard;
    private Question displayed;
    private Snapshot snapshot;
    private AsyncViewScope submission;
    private boolean sending;
    private Integer sendingChoice;
    private boolean closed;
    private FadeTransition waitingPulse;
    private ChangeListener<Boolean> reduced;
    @FXML private Label roomTitle, questionNumber, questionText, playerScore, answerStatus, selectedAnswer;
    @FXML private ProgressBar questionProgress;
    @FXML private GridPane answers;
    @FXML private StackPane timerHost;
    @FXML private VBox rankingHost;
    public QuizController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        timer = new CountdownRing(context); timerHost.getChildren().add(timer);
        leaderboard = new LeaderboardView(context, false); rankingHost.getChildren().add(leaderboard);
        for (int i = 0; i < 4; i++) { AnswerCard card = new AnswerCard(i, context, this::choose); cards.add(card); answers.add(card, i % 2, i / 2); }
        newScope();
        reduced = (p, old, value) -> updatePulse(); context.reducedMotionProperty().addListener(reduced);
    }
    private void newScope() {
        if (submission != null) { submission.close(); }
        sending = false; sendingChoice = null;
        submission = new AsyncViewScope(Platform::runLater, value -> { sending = value; render(); });
    }
    @Override public void update(Snapshot value) {
        snapshot = value;
        boolean changed = displayed != value.question();
        if (changed) {
            displayed = value.question(); newScope();
            if (displayed != null) { cards.forEach(card -> card.question(displayed)); }
        }
        roomTitle.setText(value.roomName() == null ? "Quiz arena" : value.roomName());
        playerScore.setText(value.totalScore() + " pts · your server score");
        questionNumber.setText(displayed == null ? "Waiting for QUESTION" : "QUESTION " + displayed.number() + " / " + displayed.total());
        questionText.setText(displayed == null ? "Waiting for the server's question…" : displayed.text());
        questionProgress.setProgress(displayed == null ? 0 : (double) displayed.number() / displayed.total());
        if (displayed != null) { timer.update(value.remainingSeconds() == null ? displayed.timeLimitSeconds() : value.remainingSeconds(), displayed.timeLimitSeconds(), changed); }
        leaderboard.update(value); render();
    }
    private void render() {
        if (snapshot == null || closed) { return; }
        answerStatus.setText(QuizPresentation.submission(snapshot, sending));
        Integer selected = sending ? sendingChoice : snapshot.selectedAnswer();
        selectedAnswer.setText(QuizPresentation.answer(displayed, selected));
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setDisable(sending || !QuizPresentation.canAnswer(snapshot, displayed));
            cards.get(i).selected(selected != null && selected == i);
        }
        updatePulse();
    }
    private void updatePulse() {
        boolean waiting = !closed && snapshot != null && snapshot.answerSubmitted()
                && snapshot.roundResult() == null && snapshot.errorCode() == null && !context.reducedMotion();
        if (waiting && waitingPulse == null) {
            waitingPulse = new FadeTransition(javafx.util.Duration.millis(800), answerStatus);
            waitingPulse.setFromValue(1); waitingPulse.setToValue(.65); waitingPulse.setAutoReverse(true);
            waitingPulse.setCycleCount(Animation.INDEFINITE); waitingPulse.play();
        } else if (!waiting && waitingPulse != null) { waitingPulse.stop(); waitingPulse = null; answerStatus.setOpacity(1); }
    }
    public void shortcut(int choice) { choose(displayed, choice); }
    public void choose(Question expected, int choice) {
        if (closed || sending || expected != displayed || !scenes.gameplayInputAllowed()
                || choice < 0 || choice > 3 || !QuizPresentation.canAnswer(context.state().snapshot(), expected)) { return; }
        sendingChoice = choice; cards.get(choice).selected(true);
        selectedAnswer.setText(QuizPresentation.answer(expected, choice));
        submission.run(() -> context.invoke(() -> context.requireService().submitAnswer(expected, choice)), Duration.ofSeconds(8),
                ignored -> render(), failure -> { if (!closed) { render(); scenes.notifications().show("Could not send this answer. Check the connection and server notice.", ToastNotification.Tone.ERROR); } });
    }
    @FXML private void leave() { scenes.confirmLeave(); }
    @FXML private void logout() { scenes.logout(); }
    @Override public void close() { closed = true; updatePulse(); submission.close(); timer.close(); leaderboard.close(); cards.forEach(AnswerCard::close); context.reducedMotionProperty().removeListener(reduced); }
}
