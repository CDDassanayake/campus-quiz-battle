package com.quizbattle.game;

import java.util.List;
import java.util.Objects;

/**
 * An immutable quiz question.
 *
 * <p>A question always has exactly {@value #OPTION_COUNT} answer options and a valid
 * zero-based {@code correctIndex}. Instances are safe to share between threads.</p>
 *
 * @param id           the protocol question id (unique inside a game)
 * @param category     the topic (for example {@code DNS}); may be empty but never {@code null}
 * @param text         the question text
 * @param options      exactly four answer options
 * @param correctIndex the zero-based index of the correct option
 */
public record Question(int id, String category, String text, List<String> options, int correctIndex) {

    /** Number of answer options required by the wire protocol. */
    public static final int OPTION_COUNT = 4;

    public Question {
        if (id < 0) {
            throw new IllegalArgumentException("Question id must not be negative: " + id);
        }
        category = category == null ? "" : category.trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Question text must not be blank (id " + id + ")");
        }
        Objects.requireNonNull(options, "options");
        if (options.size() != OPTION_COUNT) {
            throw new IllegalArgumentException(
                    "Question " + id + " must have exactly " + OPTION_COUNT + " options, got " + options.size());
        }
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw new IllegalArgumentException("Question " + id + " has a blank option");
            }
        }
        options = List.copyOf(options);
        if (correctIndex < 0 || correctIndex >= OPTION_COUNT) {
            throw new IllegalArgumentException(
                    "Question " + id + " correctIndex must be in [0, " + (OPTION_COUNT - 1) + "], got " + correctIndex);
        }
    }

    /**
     * Returns the text of the correct option.
     *
     * @return the correct option
     */
    public String correctOption() {
        return options.get(correctIndex);
    }
}
