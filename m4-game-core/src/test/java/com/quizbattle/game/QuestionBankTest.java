package com.quizbattle.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionBankTest {

    @Test
    void loadsTheBundledSampleBank() throws IOException {
        List<Question> questions = new LocalQuestionSource("questions/sample-questions.csv").load();
        QuestionBank bank = new QuestionBank(questions);

        assertEquals(12, bank.size());
        assertEquals(12, bank.questions().size());
        assertEquals(12, bank.questions().stream().map(Question::id).distinct().count());
    }

    @Test
    void pickReturnsTheRequestedNumberOfDistinctQuestions() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(12));

        List<Question> picked = bank.pick(5);

        assertEquals(5, picked.size());
        assertEquals(5, picked.stream().map(Question::id).distinct().count());
        assertEquals(12, bank.size(), "pick must not modify the bank");
    }

    @Test
    void pickClampsToBankSize() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(4));

        List<Question> picked = bank.pick(100);

        assertEquals(4, picked.size());
        assertEquals(4, picked.stream().map(Question::id).distinct().count());
    }

    @Test
    void pickNeverDuplicatesQuestionsWithinOneGame() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(12));

        for (int round = 0; round < 20; round++) {
            List<Question> picked = bank.pick(7);
            Set<Integer> ids = new HashSet<>();
            for (Question question : picked) {
                assertTrue(ids.add(question.id()), "duplicate question in one pick");
            }
        }
    }

    @Test
    void pickIsRandomized() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(12));
        List<Question> first = bank.pick(5);
        boolean sawDifferentOrder = false;
        for (int attempt = 0; attempt < 20 && !sawDifferentOrder; attempt++) {
            sawDifferentOrder = !bank.pick(5).equals(first);
        }
        assertTrue(sawDifferentOrder, "pick should shuffle a copy");
    }

    @Test
    void pickRejectsNonPositiveCounts() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(3));

        assertThrows(IllegalArgumentException.class, () -> bank.pick(0));
        assertThrows(IllegalArgumentException.class, () -> bank.pick(-1));
    }

    @Test
    void constructorRejectsEmptyAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new QuestionBank(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new QuestionBank(List.of(TestConfig.questions(1).get(0), TestConfig.questions(1).get(0))));
    }

    @Test
    void bankAndQuestionCollectionsAreImmutable() {
        QuestionBank bank = new QuestionBank(TestConfig.questions(2));

        assertThrows(UnsupportedOperationException.class, () -> bank.questions().add(TestConfig.questions(1).get(0)));
        Question question = bank.questions().get(0);
        assertThrows(UnsupportedOperationException.class, () -> question.options().add("Extra"));
    }

    @Test
    void questionValidatesItsData() {
        assertThrows(IllegalArgumentException.class,
                () -> new Question(-1, "Cat", "Text", List.of("A", "B", "C", "D"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Question(1, "Cat", " ", List.of("A", "B", "C", "D"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Question(1, "Cat", "Text", List.of("A", "B", "C"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Question(1, "Cat", "Text", List.of("A", "B", "C", "D"), 4));
        assertThrows(IllegalArgumentException.class,
                () -> new Question(1, "Cat", "Text", List.of("A", "B", "", "D"), 0));

        Question question = new Question(1, null, "Text", List.of("A", "B", "C", "D"), 2);
        assertEquals("", question.category());
        assertEquals("C", question.correctOption());
        assertNotEquals(1, question.correctIndex());
    }
}
