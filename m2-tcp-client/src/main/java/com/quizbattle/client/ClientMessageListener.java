package com.quizbattle.client;

import com.quizbattle.common.Message;
import java.io.IOException;

/**
 * Message/disconnection callbacks run sequentially on the TCP reader thread,
 * including WELCOME first. The optional authentication callback runs on the
 * connection worker after HELLO is sent.
 * Keep callbacks short; a future JavaFX adapter should use Platform.runLater().
 * Register before connect to observe the entire session. Listener exceptions are
 * isolated so one listener cannot stop delivery to the others.
 */
@FunctionalInterface
public interface ClientMessageListener {
    void onMessage(Message message);

    /** Runs on the connection worker after HELLO is flushed; must not block. */
    default void onAuthenticating() { }

    /** Exactly once after an authenticated session ends; null means local close. */
    default void onDisconnected(IOException failure) { }
}
