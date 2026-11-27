package com.quizbattle.client.model;

import com.quizbattle.common.Message;
import com.quizbattle.common.ServerInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Synchronized state reducer with immutable snapshots. Service callbacks and UI
 * actions use the same reducer. Observers receive ordered snapshots synchronously
 * under this monitor; keep them short and never wait for service futures here.
 * A JavaFX adapter should enqueue each snapshot with Platform.runLater().
 */
public final class ClientGameState {
    public record Snapshot(long version, GameStatus status, boolean connected, boolean authenticated,
                           String username, String serverHost, int serverPort, List<RoomInfo> availableRooms,
                           String roomId, String roomName, boolean host, Set<String> knownPlayers,
                           Question question, Integer remainingSeconds, Integer selectedAnswer,
                           boolean answerSubmitted, int totalScore, RoundResult roundResult,
                           List<PlayerScore> leaderboard, FinalResult finalResult, String notice,
                           String errorCode, String pendingAction, Instant lastPong,
                           long leaderboardRevision, long leaderboardReceivedNanos) {
        /** Source compatibility for consumers constructing snapshots before feed metadata existed. */
        public Snapshot(long version, GameStatus status, boolean connected, boolean authenticated,
                        String username, String serverHost, int serverPort, List<RoomInfo> availableRooms,
                        String roomId, String roomName, boolean host, Set<String> knownPlayers,
                        Question question, Integer remainingSeconds, Integer selectedAnswer,
                        boolean answerSubmitted, int totalScore, RoundResult roundResult,
                        List<PlayerScore> leaderboard, FinalResult finalResult, String notice,
                        String errorCode, String pendingAction, Instant lastPong) {
            this(version, status, connected, authenticated, username, serverHost, serverPort,
                    availableRooms, roomId, roomName, host, knownPlayers, question, remainingSeconds,
                    selectedAnswer, answerSubmitted, totalScore, roundResult, leaderboard, finalResult,
                    notice, errorCode, pendingAction, lastPong, 0, 0);
        }
        public Snapshot {
            availableRooms = List.copyOf(availableRooms);
            knownPlayers = Set.copyOf(knownPlayers);
            leaderboard = List.copyOf(leaderboard);
        }
    }

    private final CopyOnWriteArrayList<Consumer<Snapshot>> observers = new CopyOnWriteArrayList<>();
    private long version;
    private GameStatus status = GameStatus.DISCONNECTED;
    private boolean connected;
    private boolean authenticated;
    private String username;
    private String serverHost;
    private int serverPort;
    private List<RoomInfo> rooms = List.of();
    private String roomId;
    private String roomName;
    private boolean host;
    private final Set<String> players = new LinkedHashSet<>();
    private Question question;
    private Integer remaining;
    private Integer selected;
    private boolean submitted;
    private int score;
    private RoundResult result;
    private List<PlayerScore> leaderboard = List.of();
    private FinalResult finalResult;
    private String notice = "Disconnected.";
    private String errorCode;
    private String pending;
    private Instant lastPong;
    private long leaderboardRevision;
    private long leaderboardReceivedNanos;

    public synchronized Snapshot snapshot() {
        return new Snapshot(version, status, connected, authenticated, username, serverHost, serverPort,
                rooms, roomId, roomName, host, players, question, remaining, selected, submitted,
                score, result, leaderboard, finalResult, notice, errorCode, pending, lastPong,
                leaderboardRevision, leaderboardReceivedNanos);
    }

    public void addObserver(Consumer<Snapshot> observer) { observers.addIfAbsent(Objects.requireNonNull(observer)); }
    public void removeObserver(Consumer<Snapshot> observer) { observers.remove(observer); }

    private void publish() {
        version++;
        Snapshot snapshot = snapshot();
        for (Consumer<Snapshot> observer : observers) {
            try { observer.accept(snapshot); }
            catch (RuntimeException ignored) { /* One observer must not break state processing. */ }
        }
    }

    public synchronized void connecting(ServerInfo server) {
        resetSession();
        serverHost = server.host(); serverPort = server.gatewayPort();
        status = GameStatus.CONNECTING; notice = "Connecting to game server..."; publish();
    }

    public synchronized void authenticating() {
        status = GameStatus.AUTHENTICATING; notice = "Authenticating..."; publish();
    }

    public synchronized void transportConnected() {
        connected = true; authenticating();
    }

    public synchronized void disconnected(String description) {
        resetSession(); serverHost = null; serverPort = 0;
        status = GameStatus.DISCONNECTED; notice = description; publish();
    }

    private void resetSession() {
        connected = false; authenticated = false; username = null; rooms = List.of();
        lastPong = null; errorCode = null; clearRoom();
    }

    private void clearRoom() {
        roomId = null; roomName = null; host = false; players.clear();
        clearGame(); pending = null;
    }

    private void clearGame() {
        question = null; remaining = null; selected = null; submitted = false;
        score = 0; result = null; leaderboard = List.of(); finalResult = null;
        leaderboardRevision = 0; leaderboardReceivedNanos = 0;
    }

    public synchronized void notification(String text) {
        notice = text; errorCode = null; publish();
    }

    public synchronized void operationFailed(String text) {
        notice = text; errorCode = "CLIENT_ERROR"; publish();
    }

    public synchronized void pending(String action) {
        pending = action; notice = "Request sent: " + action; errorCode = null; publish();
    }

    /** No LEAVE reply exists: this is an explicit local navigation decision. */
    public synchronized void leaveLocally() {
        clearRoom(); status = GameStatus.LOBBY;
        notice = "LEAVE sent; returned locally to lobby (no server acknowledgement in protocol).";
        errorCode = null; publish();
    }

    public synchronized void answerSubmitted(String questionId, int index) {
        if (index < 0 || index > 3 || status != GameStatus.QUESTION_ACTIVE || question == null
                || !question.id().equals(questionId) || submitted) {
            throw new IllegalStateException("Answer is invalid, outdated or already submitted.");
        }
        selected = index; submitted = true; pending = "ANSWER";
        status = GameStatus.ANSWER_SUBMITTED;
        notice = "Answer submitted. Waiting for results..."; errorCode = null; publish();
    }

    /** Returns false for malformed data; the prior valid game data is retained. */
    public synchronized boolean receive(Message message) {
        try {
            int count = switch (message.type()) {
                case WELCOME, ROOMS, PLAYER_JOINED, PLAYER_LEFT -> 1;
                case PONG -> 0;
                case JOINED -> 3;
                case QUESTION -> 9;
                case RESULT -> 4;
                case ERROR, GAME_OVER -> 2;
                default -> throw new IllegalArgumentException();
            };
            require(message.fields().size() == count);
            List<String> f = List.copyOf(message.fields());
            if (message.type() != com.quizbattle.common.MessageType.WELCOME && !authenticated) {
                return true; // Ignore events queued from an ended session.
            }
            switch (message.type()) {
                case WELCOME -> {
                    require(!authenticated); validUsername(f.get(0));
                    connected = true; authenticated = true; username = f.get(0);
                    status = GameStatus.LOBBY; notice = "Welcome, " + username + ".";
                }
                case ROOMS -> {
                    List<RoomInfo> parsed = parseRooms(f.get(0));
                    rooms = parsed;
                    if ("LIST_ROOMS".equals(pending)) { pending = null; }
                    notice = parsed.isEmpty() ? "No rooms available." : "Room list updated.";
                }
                case JOINED -> {
                    validId(f.get(0)); require(f.get(1).matches("[A-Za-z0-9_ ]{1,24}"));
                    require(f.get(2).equals("true") || f.get(2).equals("false"));
                    // M4 also sends JOINED when promoting a host in the same room.
                    if (!f.get(0).equals(roomId)) {
                        clearRoom(); roomId = f.get(0); players.add(username);
                        status = GameStatus.WAITING_ROOM;
                    }
                    roomName = f.get(1); host = Boolean.parseBoolean(f.get(2));
                    pending = null; notice = "Joined " + roomName + (host ? " (host)." : ".");
                }
                case PLAYER_JOINED, PLAYER_LEFT -> {
                    validUsername(f.get(0));
                    if (roomId == null) { return true; }
                    if (message.type() == com.quizbattle.common.MessageType.PLAYER_JOINED) { players.add(f.get(0)); }
                    else { players.remove(f.get(0)); }
                    notice = "Known player list updated (initial roster may be incomplete).";
                }
                case QUESTION -> {
                    validId(f.get(0));
                    Question parsed = new Question(f.get(0), number(f.get(1), 1), number(f.get(2), 1),
                            f.get(3), f.subList(4, 8), number(f.get(8), 1));
                    if (roomId == null || status == GameStatus.GAME_OVER) { return true; }
                    question = parsed; remaining = parsed.timeLimitSeconds();
                    selected = null; submitted = false; result = null; pending = null;
                    status = GameStatus.QUESTION_ACTIVE; notice = "Choose an answer.";
                }
                case RESULT -> {
                    validId(f.get(0)); int correct = number(f.get(1), 0); require(correct <= 3);
                    RoundResult parsed = new RoundResult(f.get(0), correct, number(f.get(2), 0), number(f.get(3), 0));
                    if (question == null || !question.id().equals(parsed.questionId()) || status == GameStatus.GAME_OVER) {
                        return true;
                    }
                    result = parsed; score = parsed.totalScore(); remaining = 0; pending = null;
                    status = GameStatus.ROUND_RESULT; notice = "Round complete.";
                }
                case GAME_OVER -> {
                    List<PlayerScore> standings = parseScores(f.get(1));
                    String winner = f.get(0);
                    require((winner.isEmpty() && standings.isEmpty()) || standings.stream().anyMatch(p -> p.username().equals(winner)));
                    if (roomId == null) { return true; }
                    finalResult = new FinalResult(winner, standings);
                    leaderboard = standings;
                    standings.stream().filter(p -> p.username().equals(username)).findFirst().ifPresent(p -> score = p.score());
                    remaining = 0; pending = null; status = GameStatus.GAME_OVER; notice = "Battle complete.";
                }
                case PONG -> lastPong = Instant.now();
                case ERROR -> {
                    require(f.get(0).matches("[A-Z_]{1,40}"));
                    errorCode = f.get(0);
                    // Server descriptions are untrusted and may echo credentials.
                    // Validate both fields but expose a local, code-based description.
                    notice = errorDescription(errorCode);
                    if ("ANSWER".equals(pending) && status == GameStatus.ANSWER_SUBMITTED) {
                        if ("BAD_MESSAGE".equals(errorCode)) {
                            submitted = false; selected = null; status = GameStatus.QUESTION_ACTIVE;
                        }
                        // ALREADY_ANSWERED/TOO_LATE keep input locked until the next QUESTION.
                    }
                    pending = null; publish(); return true;
                }
                default -> throw new IllegalArgumentException();
            }
            errorCode = null; publish(); return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            notice = "Ignored malformed server message."; errorCode = "BAD_SERVER_MESSAGE"; publish(); return false;
        }
    }

    public synchronized void countdown(String id, int seconds) {
        if (!activeRoom(id) || question == null || status == GameStatus.ROUND_RESULT || seconds < 0
                || seconds > question.timeLimitSeconds() || (remaining != null && seconds > remaining)) { return; }
        if (!Objects.equals(remaining, seconds)) { remaining = seconds; publish(); }
    }

    public synchronized void leaderboard(String id, Map<String, Integer> scores) {
        if (!activeRoom(id)) { return; }
        try {
            List<PlayerScore> parsed = new ArrayList<>();
            Map.copyOf(scores).forEach((name, value) -> parsed.add(new PlayerScore(name, value)));
            parsed.sort(Comparator.comparingInt(PlayerScore::score).reversed().thenComparing(PlayerScore::username));
            leaderboard = List.copyOf(parsed); leaderboardRevision++;
            leaderboardReceivedNanos = System.nanoTime(); publish();
        } catch (IllegalArgumentException | NullPointerException ignored) { /* Loss-tolerant feed. */ }
    }

    private boolean activeRoom(String id) {
        return authenticated && roomId != null && roomId.equals(id)
                && (status == GameStatus.QUESTION_ACTIVE || status == GameStatus.ANSWER_SUBMITTED || status == GameStatus.ROUND_RESULT);
    }

    private static List<RoomInfo> parseRooms(String payload) {
        if (payload.isEmpty()) { return List.of(); }
        List<RoomInfo> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (String row : payload.split(";", -1)) {
            String[] f = row.split(":", -1); require(f.length == 4);
            validId(f[0]); require(ids.add(f[0])); require(f[1].matches("[A-Za-z0-9_ ]{1,24}"));
            result.add(new RoomInfo(f[0], f[1], number(f[2], 0), RoomInfo.Status.valueOf(f[3])));
        }
        return List.copyOf(result);
    }

    private static List<PlayerScore> parseScores(String payload) {
        if (payload.isEmpty()) { return List.of(); }
        List<PlayerScore> result = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (String row : payload.split(";", -1)) {
            String[] f = row.split(":", -1); require(f.length == 2); require(names.add(f[0]));
            result.add(new PlayerScore(f[0], number(f[1], 0)));
        }
        return List.copyOf(result); // Preserve the authoritative server order, including ties.
    }

    private static int number(String value, int minimum) {
        require(value.matches("[0-9]+")); int parsed = Integer.parseInt(value); require(parsed >= minimum); return parsed;
    }
    private static void validId(String value) {
        require(!value.isBlank() && value.chars().noneMatch(c -> Character.isISOControl(c) || c == ':' || c == ';'));
    }
    private static void validUsername(String value) { require(value.matches("[A-Za-z0-9_]{3,16}")); }
    private static void require(boolean condition) { if (!condition) { throw new IllegalArgumentException(); } }

    private static String errorDescription(String code) {
        return switch (code) {
            case "BAD_TOKEN", "NOT_AUTHENTICATED" -> "Authentication rejected. Disconnect and log in again.";
            case "ALREADY_CONNECTED" -> "This account is already connected.";
            case "ROOM_NOT_FOUND" -> "Room not found. Refresh the room list.";
            case "ROOM_FULL" -> "The room is full. Choose another room.";
            case "NOT_IN_ROOM" -> "The server reports that you are not in a room.";
            case "NOT_HOST" -> "Only the host can start the game.";
            case "GAME_RUNNING" -> "The game is already running.";
            case "ALREADY_ANSWERED" -> "The server already has an answer. Waiting for results...";
            case "TOO_LATE" -> "The server closed this round. Waiting for results...";
            case "BAD_MESSAGE" -> "The server rejected the request format. Check the input and retry.";
            default -> "Server rejected the request (unrecognized error code).";
        };
    }
}
