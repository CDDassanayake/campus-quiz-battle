package com.quizbattle.client.model;

import java.util.List;

public record Question(String id, int number, int total, String text,
                       List<String> options, int timeLimitSeconds) {
    public Question {
        options = List.copyOf(options);
        if (id == null || id.isBlank() || number < 1 || total < number
                || text == null || options.size() != 4 || timeLimitSeconds < 1) {
            throw new IllegalArgumentException("Invalid question data.");
        }
    }
}
