package com.quizbattle.client.ui;

import java.util.HashSet;
import java.util.Set;

/** A physical key is eligible once, until released, even across screen transitions. */
public final class AnswerKeys {
    private final Set<String> held = new HashSet<>();
    public static int choice(String key) {
        return switch (key) {
            case "DIGIT1", "NUMPAD1" -> 0;
            case "DIGIT2", "NUMPAD2" -> 1;
            case "DIGIT3", "NUMPAD3" -> 2;
            case "DIGIT4", "NUMPAD4" -> 3;
            default -> -1;
        };
    }
    public boolean press(String key) { return choice(key) >= 0 && held.add(key); }
    public void release(String key) { held.remove(key); }
}
