package com.quizbattle.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads questions from a classpath resource, falling back to a filesystem path.
 *
 * <p>The configured value is {@code game.questions.file}, for example
 * {@code questions/sample-questions.csv}. The M4 module ships that file as a classpath
 * resource, so a plain {@code mvn exec:java} run works from the repository root.</p>
 */
public final class LocalQuestionSource implements QuestionSource {

    private final String location;
    private final CsvQuestionParser parser = new CsvQuestionParser();

    /**
     * Creates a local source.
     *
     * @param location classpath resource name or a filesystem path
     */
    public LocalQuestionSource(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Question file location must not be blank");
        }
        this.location = location.trim();
    }

    @Override
    public List<Question> load() throws IOException {
        String resourceName = location.startsWith("/") ? location.substring(1) : location;

        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        if (stream == null) {
            stream = LocalQuestionSource.class.getClassLoader().getResourceAsStream(resourceName);
        }
        if (stream == null) {
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                stream = Files.newInputStream(path);
            }
        }
        if (stream == null) {
            throw new IOException("Question file not found: " + location);
        }

        List<Question> questions;
        try (InputStream in = stream; Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            questions = parser.parse(reader);
        }
        if (questions.isEmpty()) {
            throw new IOException("No valid questions found in " + location);
        }
        return questions;
    }
}
