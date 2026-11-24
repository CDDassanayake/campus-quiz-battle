package com.quizbattle.game;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Header-driven CSV parser for question files.
 *
 * <p>Required header columns (case-insensitive, any order):
 * {@code id,category,text,optionA,optionB,optionC,optionD,correctIndex}.</p>
 *
 * <p>The parser supports UTF-8 BOMs, CRLF line endings, quoted fields, commas and
 * newlines inside quoted fields, and doubled quotes ({@code ""}) inside quoted fields.
 * Malformed rows (wrong column count, non-numeric values, out-of-range answers,
 * duplicate ids, blank text/options) are skipped instead of aborting the whole file,
 * so one bad row cannot take down a game server.</p>
 *
 * <p>If the header itself is missing or does not contain all required columns, the
 * parser returns an empty list. Callers treat an empty result as "unusable source"
 * (the URL source then falls back to the local file).</p>
 */
public final class CsvQuestionParser {

    private static final Logger LOG = Logger.getLogger(CsvQuestionParser.class.getName());

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "id", "category", "text", "optiona", "optionb", "optionc", "optiond", "correctindex");

    /**
     * Parses CSV text.
     *
     * @param csv the CSV content, possibly {@code null}
     * @return an immutable list of valid questions; empty when the content has no usable header/rows
     */
    public List<Question> parse(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        String content = stripBom(csv).replace("\r\n", "\n").replace('\r', '\n');
        List<List<String>> rows = tokenize(content);
        if (rows.isEmpty()) {
            return List.of();
        }

        int headerIndex = -1;
        List<String> header = null;
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isIgnorable(row)) {
                continue;
            }
            header = row;
            headerIndex = i;
            break;
        }
        if (header == null) {
            return List.of();
        }

        Map<String, Integer> columns = indexColumns(header);
        if (columns == null) {
            LOG.warning("Question CSV header is missing one or more required columns; ignoring file");
            return List.of();
        }

        int idColumn = columns.get("id");
        int categoryColumn = columns.get("category");
        int textColumn = columns.get("text");
        int optionAColumn = columns.get("optiona");
        int optionBColumn = columns.get("optionb");
        int optionCColumn = columns.get("optionc");
        int optionDColumn = columns.get("optiond");
        int correctColumn = columns.get("correctindex");

        List<Question> questions = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();

        for (int i = headerIndex + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isIgnorable(row)) {
                continue;
            }
            if (row.size() != header.size()) {
                LOG.log(Level.FINE, "Skipping question row {0}: expected {1} columns, got {2}",
                        new Object[]{i + 1, header.size(), row.size()});
                continue;
            }
            try {
                int id = Integer.parseInt(row.get(idColumn).trim());
                String category = row.get(categoryColumn).trim();
                String text = row.get(textColumn).trim();
                List<String> options = List.of(
                        row.get(optionAColumn).trim(),
                        row.get(optionBColumn).trim(),
                        row.get(optionCColumn).trim(),
                        row.get(optionDColumn).trim());
                int correctIndex = Integer.parseInt(row.get(correctColumn).trim());

                Question question = new Question(id, category, text, options, correctIndex);
                if (!seenIds.add(id)) {
                    LOG.log(Level.FINE, "Skipping question row {0}: duplicate id {1}", new Object[]{i + 1, id});
                    continue;
                }
                questions.add(question);
            } catch (RuntimeException e) {
                // NumberFormatException, IllegalArgumentException from Question, nulls, etc.
                LOG.log(Level.FINE, "Skipping malformed question row {0}: {1}",
                        new Object[]{i + 1, e.getClass().getSimpleName()});
            }
        }
        return List.copyOf(questions);
    }

    /**
     * Parses CSV from a reader. The reader is fully consumed but not closed.
     *
     * @param reader the reader
     * @return an immutable list of valid questions
     * @throws IOException if reading fails
     */
    public List<Question> parse(Reader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            builder.append(buffer, 0, read);
        }
        return parse(builder.toString());
    }

    private static String stripBom(String content) {
        return !content.isEmpty() && content.charAt(0) == '\uFEFF' ? content.substring(1) : content;
    }

    private static Map<String, Integer> indexColumns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, i);
            }
        }
        for (String required : REQUIRED_COLUMNS) {
            if (!columns.containsKey(required)) {
                return null;
            }
        }
        return columns;
    }

    private static boolean isIgnorable(List<String> row) {
        if (row.isEmpty()) {
            return true;
        }
        boolean allBlank = true;
        for (String field : row) {
            if (!field.isBlank()) {
                allBlank = false;
                break;
            }
        }
        if (allBlank) {
            return true;
        }
        return row.size() == 1 && row.get(0).trim().startsWith("#");
    }

    /**
     * Splits CSV text into rows of fields. A row with an unterminated quote or a quote in
     * the middle of an unquoted field is dropped.
     */
    private static List<List<String>> tokenize(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean malformed = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                if (field.length() == 0) {
                    inQuotes = true;
                } else {
                    malformed = true;
                    field.append(c);
                }
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString().trim());
                if (!malformed) {
                    rows.add(List.copyOf(row));
                }
                row = new ArrayList<>();
                field.setLength(0);
                malformed = false;
            } else {
                field.append(c);
            }
        }

        if (inQuotes) {
            malformed = true;
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            if (!malformed) {
                rows.add(List.copyOf(row));
            }
        }
        return rows;
    }
}
