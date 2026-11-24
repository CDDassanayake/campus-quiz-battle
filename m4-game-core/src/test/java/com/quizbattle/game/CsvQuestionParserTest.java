package com.quizbattle.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvQuestionParserTest {

    private static final String HEADER =
            "id,category,text,optionA,optionB,optionC,optionD,correctIndex";

    private final CsvQuestionParser parser = new CsvQuestionParser();

    @Test
    void parsesTheBundledSampleFile() throws IOException {
        String csv = readSampleResource();

        List<Question> questions = parser.parse(csv);

        assertEquals(12, questions.size());
        Question first = questions.get(0);
        assertEquals(1, first.id());
        assertEquals("IP", first.category());
        assertEquals("How many bits are in an IPv4 address?", first.text());
        assertEquals(List.of("16", "32", "64", "128"), first.options());
        assertEquals(1, first.correctIndex());
        assertEquals("32", first.correctOption());
    }

    @Test
    void handlesUtf8BomAndCrlfLineEndings() {
        String csv = "\uFEFF" + HEADER + "\r\n"
                + "1,Net,\"What is a LAN?\",A,B,C,D,2\r\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals("What is a LAN?", questions.get(0).text());
    }

    @Test
    void handlesCommasInsideQuotedFields() {
        String csv = HEADER + "\n"
                + "7,Quotes,\"What is 1, 2 or 3?\",A,B,C,D,2\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals("What is 1, 2 or 3?", questions.get(0).text());
    }

    @Test
    void handlesDoubledQuotesInsideQuotedFields() {
        String csv = HEADER + "\n"
                + "8,Quotes,\"Say \"\"hello\"\" now\",A,B,C,D,0\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals("Say \"hello\" now", questions.get(0).text());
    }

    @Test
    void handlesNewlinesInsideQuotedFields() {
        String csv = HEADER + "\n"
                + "9,Multi,\"Line one\nLine two\",A,B,C,D,1\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals("Line one\nLine two", questions.get(0).text());
    }

    @Test
    void headerOrderAndCaseDoNotMatter() {
        String csv = "category,correctIndex,text,optionA,optionB,optionC,optionD,id,notes\n"
                + "Cat,3,Question?,A,B,C,D,42,ignored\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals(42, questions.get(0).id());
        assertEquals(3, questions.get(0).correctIndex());
        assertEquals("Cat", questions.get(0).category());
    }

    @Test
    void skipsMalformedRowsButKeepsGoodOnes() {
        String csv = HEADER + "\n"
                + "1,Good,\"Valid, row\",A,B,C,D,0\n"
                + "x,Bad,Not a number,A,B,C,D,0\n"
                + "2,Bad,Index too high,A,B,C,D,9\n"
                + "3,Bad,Too few columns,A,B,C\n"
                + "4,Bad, ,A,B,C,D,0\n"
                + "5,Bad,Empty option,A,B,,D,0\n"
                + "6,Good,Second valid,A,B,C,D,1\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(2, questions.size());
        assertEquals(1, questions.get(0).id());
        assertEquals(6, questions.get(1).id());
    }

    @Test
    void skipsDuplicateQuestionIds() {
        String csv = HEADER + "\n"
                + "1,First,First question,A,B,C,D,0\n"
                + "1,Second,Second question,A,B,C,D,0\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
        assertEquals("First question", questions.get(0).text());
    }

    @Test
    void ignoresBlankLinesAndComments() {
        String csv = "\n# a comment\n" + HEADER + "\n\n"
                + "1,Good,Question,A,B,C,D,0\n"
                + "\n";

        List<Question> questions = parser.parse(csv);

        assertEquals(1, questions.size());
    }

    @Test
    void missingRequiredHeaderReturnsEmptyList() {
        String csv = "id,category,text,optionA\n1,Test,Question,A\n";

        assertTrue(parser.parse(csv).isEmpty());
    }

    @Test
    void nullAndEmptyContentReturnEmptyList() {
        assertTrue(parser.parse((String) null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   \n\n").isEmpty());
    }

    @Test
    void parsesFromReader() throws IOException {
        String csv = HEADER + "\n1,Test,Question,A,B,C,D,0\n";

        List<Question> questions = parser.parse(new StringReader(csv));

        assertEquals(1, questions.size());
        assertNotNull(questions.get(0).text());
    }

    @Test
    void unterminatedQuoteDropsOnlyThatRow() {
        String csv = HEADER + "\n"
                + "1,Bad,\"never closed,A,B,C,D,0\n"
                + "2,Good,Fine,A,B,C,D,0\n";

        List<Question> questions = parser.parse(csv);

        // The unterminated row consumes the rest of the file in strict CSV terms, so the
        // parser must not crash and must not invent a question from it.
        assertTrue(questions.stream().noneMatch(question -> question.id() == 1));
    }

    private String readSampleResource() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/questions/sample-questions.csv")) {
            assertNotNull(in, "sample-questions.csv must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
