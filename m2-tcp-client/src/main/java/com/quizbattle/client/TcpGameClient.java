package com.quizbattle.client;

import com.quizbattle.common.Message;
import com.quizbattle.common.MessageCodec;
import com.quizbattle.common.MessageType;
import com.quizbattle.common.ProtocolException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One authenticated TCP session. connect() and sends block the caller and must
 * run on a worker thread in a GUI. close() cancels socket I/O without waiting
 * for callbacks. Closed/failed clients cannot be reused; create a new instance.
 * Authentication failures are thrown by connect(), before reader callbacks begin.
 */
public final class TcpGameClient implements AutoCloseable {
    public enum State { NEW, CONNECTING, AUTHENTICATED, CLOSED }

    private final String host;
    private final int port;
    private String token;
    private final ClientTimeouts timeouts;
    private final Object lifecycle = new Object();
    private final Object writeLock = new Object();
    private final CopyOnWriteArrayList<ClientMessageListener> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.NEW;
    private volatile String username;
    private volatile InetAddress resolvedAddress;
    // Socket publication/state transitions are guarded by lifecycle. Streams
    // are published before AUTHENTICATED; output is used only under writeLock.
    private Socket socket;
    private ProtocolLineReader input;
    private OutputStream output;
    private IOException terminalFailure;

    public TcpGameClient(String host, int port, String token) {
        this(host, port, token, ClientTimeouts.defaults());
    }

    public TcpGameClient(String host, int port, String token, ClientTimeouts timeouts) {
        if (host == null || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)
                || host.contains("/") || host.contains("|") || host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Enter a valid server hostname or IP address.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Server port must be between 1 and 65535.");
        }
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("An authentication token is required.");
        }
        this.host = host;
        this.port = port;
        this.token = token;
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        encode(new Message(MessageType.HELLO, List.of(token)));
    }

    public void addListener(ClientMessageListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(ClientMessageListener listener) {
        listeners.remove(listener);
    }

    public State state() { return state; }
    public boolean isConnected() { return state == State.AUTHENTICATED; }
    public boolean isAuthenticated() { return state == State.AUTHENTICATED; }
    public Optional<String> username() { return Optional.ofNullable(username); }
    public Optional<InetAddress> resolvedAddress() { return Optional.ofNullable(resolvedAddress); }

    public void connect() throws IOException {
        Socket connection;
        String authenticationToken;
        synchronized (lifecycle) {
            if (state != State.NEW) {
                throw new IOException("Client already used; create a new client for another connection.");
            }
            state = State.CONNECTING;
            connection = new Socket();
            socket = connection;
            authenticationToken = token;
            token = null;
        }
        boolean authenticating = false;
        try {
            resolvedAddress = InetAddress.getByName(host);
            System.getLogger(TcpGameClient.class.getName()).log(System.Logger.Level.INFO,
                    "Resolved game server IP: {0}", resolvedAddress.getHostAddress());
            connection.connect(new InetSocketAddress(resolvedAddress, port), timeouts.connectMillis());
            authenticating = true;
            input = new ProtocolLineReader(new BufferedInputStream(connection.getInputStream()));
            output = new BufferedOutputStream(connection.getOutputStream());
            connection.setSoTimeout(timeouts.authenticationMillis());
            synchronized (writeLock) {
                output.write(encode(new Message(MessageType.HELLO, List.of(authenticationToken))));
                output.flush();
            }
            for (ClientMessageListener listener : listeners) {
                try { listener.onAuthenticating(); }
                catch (RuntimeException ignored) { /* Keep authentication independent of observers. */ }
            }
            Message welcome = input.read(connection,
                    System.nanoTime() + timeouts.authenticationMillis() * 1_000_000L);
            if (welcome == null) {
                throw new IOException("Server disconnected during authentication.");
            }
            if (welcome.type() == MessageType.ERROR) {
                if (welcome.fields().size() == 2 && "BAD_TOKEN".equals(welcome.getField(0))) {
                    throw new IOException("Authentication rejected: BAD_TOKEN. Log in again.");
                }
                throw new IOException("Server rejected authentication.");
            }
            if (welcome.type() != MessageType.WELCOME || welcome.fields().size() != 1
                    || !welcome.getField(0).matches("[A-Za-z0-9_]{3,16}")) {
                throw new IOException("Expected WELCOME with a valid username during authentication.");
            }
            connection.setSoTimeout(timeouts.readMillis());
            synchronized (lifecycle) {
                if (state == State.CLOSED) {
                    throw new IOException("Connection was closed.");
                }
                username = welcome.getField(0);
                state = State.AUTHENTICATED;
                Thread reader = new Thread(() -> readLoop(connection, welcome), "quizbattle-tcp-reader");
                reader.setDaemon(true);
                reader.start();
            }
        } catch (UnknownHostException e) {
            close();
            throw new IOException("Server hostname could not be resolved.");
        } catch (ConnectException e) {
            close();
            throw new IOException("Connection refused or server unavailable; check the server address and port.");
        } catch (SocketTimeoutException e) {
            close();
            throw new IOException(authenticating ? "Authentication timed out waiting for WELCOME."
                    : "Connection to the server timed out.");
        } catch (IOException e) {
            close();
            // All protocol messages above are sanitized; socket errors contain
            // no wire data. Avoid preserving nested exceptions from libraries.
            throw new IOException(e.getMessage());
        }
    }

    /** Only game commands are allowed; HELLO is managed by connect(). */
    public void send(Message message) throws IOException {
        Objects.requireNonNull(message, "message");
        Message snapshot = new Message(message.type(), List.copyOf(message.fields()));
        validateCommand(snapshot);
        byte[] bytes = encode(snapshot);
        synchronized (writeLock) {
            if (state != State.AUTHENTICATED) {
                throw new IOException("Cannot send: client is not authenticated or is disconnected.");
            }
            try {
                output.write(bytes);
                output.flush();
            } catch (IOException e) {
                IOException failure = new IOException("Failed to send message; connection closed.");
                shutdown(failure);
                throw failure;
            }
        }
    }

    public void listRooms() throws IOException { send(command(MessageType.LIST_ROOMS)); }
    public void createRoom(String roomName, int questionCount) throws IOException {
        send(command(MessageType.CREATE_ROOM, roomName, Integer.toString(questionCount)));
    }
    public void join(String roomId) throws IOException { send(command(MessageType.JOIN, roomId)); }
    public void leave() throws IOException { send(command(MessageType.LEAVE)); }
    public void start() throws IOException { send(command(MessageType.START)); }
    public void answer(String questionId, int choiceIndex) throws IOException {
        send(command(MessageType.ANSWER, questionId, Integer.toString(choiceIndex)));
    }
    public void ping() throws IOException { send(command(MessageType.PING)); }

    private static Message command(MessageType type, String... fields) {
        for (String field : fields) {
            if (field == null) {
                throw new IllegalArgumentException("Command fields must not be null.");
            }
        }
        return new Message(type, List.of(fields));
    }

    private static void validateCommand(Message m) {
        int count = switch (m.type()) {
            case PING, LIST_ROOMS, LEAVE, START -> 0;
            case JOIN -> 1;
            case CREATE_ROOM, ANSWER -> 2;
            default -> throw new IllegalArgumentException("Only TCP game commands may be sent.");
        };
        if (m.fields().size() != count) {
            throw new IllegalArgumentException("Incorrect number of command fields.");
        }
        if (m.type() == MessageType.CREATE_ROOM) {
            if (!m.getField(0).matches("[A-Za-z0-9_ ]{1,24}") || integer(m.getField(1)) <= 0) {
                throw new IllegalArgumentException("Room name must use 1-24 letters, digits, underscores or spaces; question count must be positive.");
            }
        } else if (m.type() == MessageType.JOIN || m.type() == MessageType.ANSWER) {
            String id = m.getField(0);
            if (id.isBlank() || id.indexOf('\r') >= 0 || id.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("A nonblank room/question ID without line breaks is required.");
            }
            if (m.type() == MessageType.ANSWER) {
                int choice = integer(m.getField(1));
                if (choice < 0 || choice > 3) {
                    throw new IllegalArgumentException("Answer choice must be 0 (A), 1 (B), 2 (C) or 3 (D).");
                }
            }
        }
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected a valid integer."); }
    }

    private static byte[] encode(Message message) {
        for (String field : message.fields()) {
            if (field == null || field.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Protocol fields cannot be null or contain carriage returns.");
            }
        }
        try {
            return (MessageCodec.encode(message) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (ProtocolException e) {
            throw new IllegalArgumentException("Outgoing message exceeds the 4096-byte limit.");
        }
    }

    private void readLoop(Socket connection, Message welcome) {
        try {
            deliver(welcome);
            while (state == State.AUTHENTICATED) {
                Message message = input.read(connection, 0);
                if (message == null) {
                    throw new IOException("Server disconnected unexpectedly.");
                }
                int expectedFields = switch (message.type()) {
                    case WELCOME, ROOMS, PLAYER_JOINED, PLAYER_LEFT -> 1;
                    case PONG -> 0;
                    case ERROR, GAME_OVER -> 2;
                    case JOINED -> 3;
                    case RESULT -> 4;
                    case QUESTION -> 9;
                    default -> throw new IOException("Unexpected message type on the game channel.");
                };
                if (message.fields().size() != expectedFields) {
                    throw new IOException("Server message has an invalid field count.");
                }
                if (state == State.AUTHENTICATED) {
                    deliver(message);
                }
            }
        } catch (SocketTimeoutException e) {
            shutdown(new IOException("Timed out waiting for a server message."));
        } catch (IOException e) {
            shutdown(new IOException(e.getMessage()));
        } finally {
            shutdown(null);
            IOException failure;
            synchronized (lifecycle) { failure = terminalFailure; }
            for (ClientMessageListener listener : listeners) {
                try { listener.onDisconnected(failure); }
                catch (RuntimeException ignored) { /* Listener owns its error handling. */ }
            }
        }
    }

    private void deliver(Message message) {
        for (ClientMessageListener listener : listeners) {
            try { listener.onMessage(message); }
            catch (RuntimeException ignored) { /* Keep other listeners and reader alive. */ }
        }
    }

    private void shutdown(IOException failure) {
        synchronized (lifecycle) {
            if (state == State.CLOSED) { return; }
            terminalFailure = failure;
            state = State.CLOSED;
            username = null;
            token = null;
            if (socket != null) {
                try { socket.close(); }
                catch (IOException ignored) { /* Already closed or transport failed. */ }
            }
        }
    }

    @Override
    public void close() { shutdown(null); }
}
