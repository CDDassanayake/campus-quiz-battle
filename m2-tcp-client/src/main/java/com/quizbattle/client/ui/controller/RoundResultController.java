package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.*;
import com.quizbattle.client.ui.components.LeaderboardView;
import com.quizbattle.client.ui.components.AnswerCard;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;

public final class RoundResultController implements ViewController {
    private final UiContext context;
    private final SceneManager scenes;
    private LeaderboardView leaderboard;
    private ScoreMotion points;
    private com.quizbattle.client.model.RoundResult rendered;
    private final java.util.List<AnswerCard> cards = new java.util.ArrayList<>();
    @FXML private Label resultHeading, resultQuestion, correctAnswer, yourAnswer, awardedPoints, totalScore;
    @FXML private VBox rankingHost, feedbackPanel;
    @FXML private GridPane resultAnswers;
    public RoundResultController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    @FXML private void initialize() {
        leaderboard = new LeaderboardView(context, false); rankingHost.getChildren().add(leaderboard);
        points = new ScoreMotion(awardedPoints, context);
        for (int i = 0; i < 4; i++) { var card = new AnswerCard(i, context, (q, choice) -> { }); card.setDisable(true); cards.add(card); resultAnswers.add(card, i % 2, i / 2); }
    }
    @Override public void update(Snapshot s) {
        leaderboard.update(s);
        if (s.question() == null || s.roundResult() == null || rendered == s.roundResult()) { return; }
        rendered = s.roundResult();
        resultHeading.setText(QuizPresentation.outcome(s));
        resultQuestion.setText("QUESTION " + s.question().number() + " / " + s.question().total() + " · " + s.question().text());
        correctAnswer.setText("✓ Correct answer   " + QuizPresentation.answer(s.question(), s.roundResult().correctIndex()));
        yourAnswer.setText("Your selection   " + QuizPresentation.answer(s.question(), s.selectedAnswer()));
        points.show(s.roundResult().pointsAwarded()); totalScore.setText(s.roundResult().totalScore() + " total points");
        cards.forEach(card -> { card.question(s.question()); card.outcome(s.roundResult().correctIndex(), s.selectedAnswer()); });
        feedbackPanel.getStyleClass().removeAll("result-correct", "result-incorrect", "result-unanswered");
        feedbackPanel.getStyleClass().add(s.selectedAnswer() == null ? "result-unanswered" : s.selectedAnswer() == s.roundResult().correctIndex() ? "result-correct" : "result-incorrect");
    }
    @FXML private void leave() { scenes.confirmLeave(); }
    @FXML private void logout() { scenes.logout(); }
    @Override public void close() { leaderboard.close(); points.close(); cards.forEach(AnswerCard::close); }
}
