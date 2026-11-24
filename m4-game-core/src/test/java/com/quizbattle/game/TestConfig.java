package com.quizbattle.game;

import com.quizbattle.common.AppConfig;
import com.quizbattle.common.Message;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Test configuration and fixture helpers.
 *
 * <p>Configuration is written to a temporary properties file because {@link AppConfig}
 * only exposes {@code load(String)}. Defaults favour fast, deterministic tests: a short
 * round gap, no leaderboard throttling and a small handler pool.</p>
 */
final class TestConfig {

    private TestConfig() {
    }

    /**
     * Creates a test {@link AppConfig}. Values are supplied as alternating
     * {@code key, value} strings and override the test defaults.
     */
    static AppConfig create(String... keyValues) throws IOException {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Properties properties = new Properties();
        properties.setProperty("game.handler.pool.size", "8");
        properties.setProperty("game.question.time.seconds", "5");
        properties.setProperty("game.questions.file", "questions/sample-questions.csv");
        properties.setProperty("game.questions.url", "");
        properties.setProperty("game.room.max.players", "10");
        properties.setProperty("game.round.gap.millis", "5");
        properties.setProperty("game.leaderboard.throttle.millis", "0");

        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }

        Path file = Files.createTempFile("m4-test", ".properties");
        try {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "M4 test configuration");
            }
            return AppConfig.load(file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Creates {@code count} questions with ids 1..count and correct index 1. */
    static List<Question> questions(int count) {
        List<Question> questions = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            questions.add(new Question(
                    i,
                    "Test",
                    "Question " + i + "?",
                    List.of("Red", "Green", "Blue", "Yellow"),
                    1));
        }
        return List.copyOf(questions);
    }

    static QuestionSource source(int count) {
        List<Question> questions = questions(count);
        return () -> questions;
    }

    static QuestionSource source(List<Question> questions) {
        List<Question> copy = List.copyOf(questions);
        return () -> copy;
    }

    /** Parses the standings field of GAME_OVER ({@code name:score;name:score}). */
    static Map<String, Integer> standings(Message gameOver) {
        Map<String, Integer> standings = new LinkedHashMap<>();
        String encoded = gameOver.getField(1);
        if (encoded.isEmpty()) {
            return standings;
        }
        for (String entry : encoded.split(";")) {
            int separator = entry.lastIndexOf(':');
            standings.put(entry.substring(0, separator), Integer.parseInt(entry.substring(separator + 1)));
        }
        return standings;
    }

    /** Points awarded field of a RESULT message. */
    static int points(Message result) {
        return Integer.parseInt(result.getField(2));
    }

    /** Total score field of a RESULT message. */
    static int total(Message result) {
        return Integer.parseInt(result.getField(3));
    }

    /** A controllable clock for deterministic scoring tests. */
    static final class MutableClock implements LongSupplier {

        private final AtomicLong nanos;

        MutableClock(long startNanos) {
            this.nanos = new AtomicLong(startNanos);
        }

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        void set(long value) {
            nanos.set(value);
        }
    }
}
