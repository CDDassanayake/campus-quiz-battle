package com.quizbattle.client.model;

import java.util.List;

public record FinalResult(String winner, List<PlayerScore> standings) {
    public FinalResult { standings = List.copyOf(standings); }
}
