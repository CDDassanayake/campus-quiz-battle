package com.quizbattle.client.model;

public record RoundResult(String questionId, int correctIndex, int pointsAwarded, int totalScore) { }
