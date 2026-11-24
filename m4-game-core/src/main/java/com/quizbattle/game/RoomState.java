package com.quizbattle.game;

/**
 * Lifecycle of a room.
 *
 * <pre>
 * LOBBY --> RUNNING --> FINISHED
 * </pre>
 *
 * <p>Players may join only in {@code LOBBY}. Leaving is allowed in every state.
 * A {@code FINISHED} room is removed once its last player leaves.</p>
 */
public enum RoomState {
    LOBBY,
    RUNNING,
    FINISHED
}
