package com.quizbattle.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable collection of validated questions.
 *
 * <p>Question ids are unique inside a bank so that clients can refer to a question
 * unambiguously in {@code ANSWER|questionId|choiceIndex}.</p>
 */
public final class QuestionBank {

    private final List<Question> questions;

    /**
     * Creates a bank.
     *
     * @param questions the questions; must not be empty and ids must be unique
     * @throws IllegalArgumentException if the bank is empty or contains duplicate ids
     */
    public QuestionBank(List<Question> questions) {
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("A question bank must contain at least one question");
        }
        Set<Integer> ids = new HashSet<>();
        for (Question question : questions) {
            Objects.requireNonNull(question, "question");
            if (!ids.add(question.id())) {
                throw new IllegalArgumentException("Duplicate question id: " + question.id());
            }
        }
        this.questions = List.copyOf(questions);
    }

    /**
     * Returns all questions in their original order.
     *
     * @return an immutable list
     */
    public List<Question> questions() {
        return questions;
    }

    /**
     * Returns the number of questions in the bank.
     *
     * @return the size
     */
    public int size() {
        return questions.size();
    }

    /**
     * Returns a random, duplicate-free selection of questions.
     *
     * <p>The bank itself is not modified: a copy is shuffled with
     * {@link Collections#shuffle(List)}. If {@code count} exceeds the bank size the
     * whole bank is returned in random order.</p>
     *
     * @param count the requested number of questions, at least 1
     * @return an immutable list of distinct questions
     * @throws IllegalArgumentException if {@code count} is less than 1
     */
    public List<Question> pick(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        List<Question> shuffled = new ArrayList<>(questions);
        Collections.shuffle(shuffled);
        int selected = Math.min(count, shuffled.size());
        return List.copyOf(shuffled.subList(0, selected));
    }
}
