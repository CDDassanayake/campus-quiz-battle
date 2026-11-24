package com.quizbattle.game;

import com.quizbattle.common.ClientSession;
import com.quizbattle.common.Message;
import com.quizbattle.common.MessageType;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe fake {@link ClientSession} that records every message sent to it.
 *
 * <p>{@link #awaitMessage(MessageType, int, long)} performs a bounded wait (monitor
 * notify/wait), so tests do not need arbitrary sleeps.</p>
 */
final class RecordingClientSession implements ClientSession {

    private final String id;
    private final String username;
    private final SocketAddress remoteAddress;
    private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object monitor = new Object();

    RecordingClientSession(String username) {
        this("session-" + username, username);
    }

    RecordingClientSession(String id, String username) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = Objects.requireNonNull(username, "username");
        this.remoteAddress = new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> username() {
        return Optional.of(username);
    }

    @Override
    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public void send(Message message) {
        Objects.requireNonNull(message, "message");
        messages.add(message);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @Override
    public void close() {
        closed.set(true);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    String usernameValue() {
        return username;
    }

    int messageCount() {
        return messages.size();
    }

    List<Message> messages() {
        return List.copyOf(messages);
    }

    List<Message> messages(MessageType type) {
        List<Message> matching = new ArrayList<>();
        for (Message message : messages) {
            if (message.type() == type) {
                matching.add(message);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    int count(MessageType type) {
        int count = 0;
        for (Message message : messages) {
            if (message.type() == type) {
                count++;
            }
        }
        return count;
    }

    Message last(MessageType type) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.type() == type) {
                return message;
            }
        }
        return null;
    }

    /**
     * Waits until at least {@code count} messages of {@code type} have been received.
     *
     * @return {@code true} if the count was reached before the timeout
     */
    boolean awaitMessage(MessageType type, int count, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (monitor) {
            while (count(type) < count) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    monitor.wait(Math.max(1L, remainingNanos / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Waits for a message and returns it, or {@code null} on timeout. */
    Message awaitLast(MessageType type, int count, long timeoutMillis) {
        return awaitMessage(type, count, timeoutMillis) ? messages(type).get(count - 1) : null;
    }

    boolean awaitGameOver(long timeoutMillis) {
        return awaitMessage(MessageType.GAME_OVER, 1, timeoutMillis);
    }

    Message errorWithCode(String code) {
        for (Message message : messages(MessageType.ERROR)) {
            if (message.fieldCount() == 2 && code.equals(message.getField(0))) {
                return message;
            }
        }
        return null;
    }

    int errorCount(String... codes) {
        int count = 0;
        for (Message message : messages(MessageType.ERROR)) {
            if (message.fieldCount() < 1) {
                continue;
            }
            for (String code : codes) {
                if (code.equals(message.getField(0))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
