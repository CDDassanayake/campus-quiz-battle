package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;

/** SceneManager owns a view's lifecycle and forwards its one state subscription. */
public interface ViewController extends AutoCloseable {
    default void shown() { }
    default void update(Snapshot snapshot) { }
    @Override default void close() { }
}
