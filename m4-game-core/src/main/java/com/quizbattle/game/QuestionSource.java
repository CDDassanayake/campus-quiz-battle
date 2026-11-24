package com.quizbattle.game;

import java.io.IOException;
import java.util.List;

/**
 * Supplies the questions used by a game.
 *
 * <p>Implementations are called once, eagerly, when a {@link RoomManager} is created.
 * They must never be called on the selector thread or while a room lock is held.</p>
 */
public interface QuestionSource {

    /**
     * Loads the available questions.
     *
     * @return an immutable list; may be empty when the source had no usable rows
     * @throws IOException if the source cannot be read at all
     */
    List<Question> load() throws IOException;
}
