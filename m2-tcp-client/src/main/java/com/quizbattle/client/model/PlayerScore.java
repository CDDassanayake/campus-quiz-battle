package com.quizbattle.client.model;

public record PlayerScore(String username, int score) {
    public PlayerScore {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}") || score < 0) {
            throw new IllegalArgumentException("Invalid player score.");
        }
    }
}
