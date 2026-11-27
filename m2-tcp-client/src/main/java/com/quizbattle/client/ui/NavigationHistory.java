package com.quizbattle.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Pure navigation policy; authentication is read from the application snapshot. */
public final class NavigationHistory {
    private final Deque<Screen> history = new ArrayDeque<>();
    private Screen current = Screen.WELCOME;
    public Screen current() { return current; }
    public Screen navigate(Screen requested, boolean authenticated) {
        Objects.requireNonNull(requested);
        Screen target = requested.requiresAuthentication() && !authenticated ? Screen.LOGIN : requested;
        if (target != current) { history.push(current); current = target; }
        return current;
    }
    public Screen back(boolean authenticated) {
        while (!history.isEmpty()) {
            Screen previous = history.pop();
            if (!previous.requiresAuthentication() || authenticated) { current = previous; return current; }
        }
        current = Screen.WELCOME; return current;
    }
}
