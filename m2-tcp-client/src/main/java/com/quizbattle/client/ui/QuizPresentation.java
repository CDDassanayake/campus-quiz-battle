package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.model.Question;

/** Text and eligibility derived from server state; no scoring or deadline decisions. */
public final class QuizPresentation {
    private QuizPresentation() { }
    public static boolean canAnswer(Snapshot s, Question displayed) {
        return s.authenticated() && s.connected() && displayed != null && s.question() == displayed
                && s.status() == GameStatus.QUESTION_ACTIVE && !s.answerSubmitted();
    }
    public static String answer(Question q, Integer index) {
        return q == null || index == null ? "No answer submitted locally" :
                "ABCD".charAt(index) + " · " + q.options().get(index);
    }
    public static String outcome(Snapshot s) {
        if (s.roundResult() == null || s.question() == null) { return "Waiting for the round result"; }
        if (s.selectedAnswer() == null) { return "Unanswered"; }
        return s.selectedAnswer() == s.roundResult().correctIndex() ? "Correct selection" : "Incorrect selection";
    }
    public static String submission(Snapshot s, boolean sending) {
        if (sending) { return "Sending answer…"; }
        if (s.errorCode() != null) { return "Server / client notice · " + s.errorCode() + "\n" + s.notice(); }
        return s.answerSubmitted() ? "Answer sent. Waiting for the round to finish…\nTCP write completed. Await the authoritative server result."
                : "Choose your answer · click a card or press 1–4";
    }
}
