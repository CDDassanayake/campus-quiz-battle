package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.model.PlayerScore;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.common.AppConfig;
import com.quizbattle.common.ServerInfo;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One input owner; service observers render asynchronous updates without reading input. */
public final class ConsoleClient implements AutoCloseable {
    public interface Input {
        String readLine() throws IOException;
        char[] readPassword() throws IOException;
    }

    private enum Screen { HOME, SERVERS, AUTH }
    private final GameClientService service;
    private final Input input;
    private final PrintWriter output;
    private final AppConfig config;
    private final Object outputLock = new Object();
    private final Consumer<Snapshot> observer = this::render;
    private volatile Screen screen = Screen.HOME;
    private List<ServerInfo> servers = List.of();
    private ServerInfo selected;
    private volatile boolean running = true;
    private Snapshot displayed;

    public ConsoleClient(GameClientService service, Input input, PrintWriter output, AppConfig config) {
        this.service = Objects.requireNonNull(service); this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output); this.config = Objects.requireNonNull(config);
    }

    /** Reader fallback is useful in IDEs and tests; passwords are echoed by such terminals. */
    public ConsoleClient(GameClientService service, Reader input, PrintWriter output, AppConfig config) {
        this(service, readerInput(input), output, config);
    }

    private static Input readerInput(Reader source) {
        BufferedReader reader = new BufferedReader(source);
        return new Input() {
            @Override public String readLine() throws IOException { return reader.readLine(); }
            @Override public char[] readPassword() throws IOException {
                String line = reader.readLine(); return line == null ? null : line.toCharArray();
            }
        };
    }

    public static ConsoleClient system(GameClientService service, AppConfig config) {
        Console terminal = System.console();
        if (terminal == null) {
            PrintWriter writer = new PrintWriter(System.out, true);
            writer.println("Terminal password masking unavailable; input will be visible in this IDE/redirected terminal.");
            return new ConsoleClient(service, new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8), writer, config);
        }
        return new ConsoleClient(service, new Input() {
            @Override public String readLine() { return terminal.readLine(); }
            @Override public char[] readPassword() { return terminal.readPassword(); }
        // Console.writer() shares a lock with Console.readLine(), which can
        // hold that lock while waiting for input. System.out keeps asynchronous
        // questions and disconnect notifications visible during that wait.
        }, new PrintWriter(System.out, true), config);
    }

    /** Blocks this input-owner thread, never the TCP reader or service event thread. */
    public void run() throws IOException {
        service.state().addObserver(observer);
        home();
        try {
            while (running) {
                String line = input.readLine();
                if (line == null) { break; }
                try { handle(line.trim()); }
                catch (IllegalArgumentException | IllegalStateException e) { print("Invalid input: " + e.getMessage()); }
                catch (ExecutionException e) { print("Request failed. " + e.getCause().getMessage()); }
                catch (TimeoutException e) { service.disconnect(); print("Request timed out. Disconnecting; try again."); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (IOException e) { if (running) { print("Console input failed."); } break; }
            }
        } finally { close(); }
    }

    private void handle(String line) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        if (line.equalsIgnoreCase("exit")) { running = false; return; }
        if (line.equalsIgnoreCase("disconnect") || line.equalsIgnoreCase("logout")) {
            await(service.disconnect()); screen = Screen.HOME; home(); return;
        }
        Snapshot s = service.state().snapshot();
        if (!s.authenticated()) { handleDisconnected(line); return; }
        if (line.equalsIgnoreCase("ping")) { await(service.ping()); return; }
        if (line.equalsIgnoreCase("leave") || line.equalsIgnoreCase("lobby")) {
            await(service.leaveRoom()); return;
        }
        if (line.startsWith("answer ")) {
            String[] fields = line.split("\\s+");
            if (fields.length != 3) { throw new IllegalArgumentException("Use: answer <questionId> <1-4>."); }
            await(service.submitAnswer(fields[1], answerIndex(fields[2]))); return;
        }
        switch (s.status()) {
            case LOBBY -> {
                switch (menuChoice(line, 1, 5)) {
                    case 1 -> await(service.listRooms());
                    case 2 -> {
                        String name = read("Room name (1-24 letters/digits/underscores/spaces):");
                        int count = positive(read("Number of questions:"));
                        await(service.createRoom(name, count));
                    }
                    case 3 -> await(service.joinRoom(read("Room ID:")));
                    case 4 -> { await(service.disconnect()); screen = Screen.HOME; home(); }
                    case 5 -> running = false;
                    default -> throw new IllegalArgumentException();
                }
            }
            case WAITING_ROOM -> {
                if (line.equalsIgnoreCase("start") || line.equals("1")) { await(service.startGame()); }
                else { throw new IllegalArgumentException("Host: start. Everyone: leave, disconnect or exit."); }
            }
            case QUESTION_ACTIVE, ANSWER_SUBMITTED -> throw new IllegalArgumentException(
                    "Use answer " + s.question().id() + " <1-4>. A question ID is required to reject outdated input.");
            case ROUND_RESULT -> print("Waiting for the next QUESTION or GAME_OVER. You may leave, disconnect or exit.");
            case GAME_OVER -> {
                switch (menuChoice(line, 1, 3)) {
                    case 1 -> await(service.leaveRoom());
                    case 2 -> { await(service.disconnect()); screen = Screen.HOME; home(); }
                    case 3 -> running = false;
                    default -> throw new IllegalArgumentException();
                }
            }
            default -> print("Please wait for the connection.");
        }
    }

    private void handleDisconnected(String line) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        switch (screen) {
            case HOME -> {
                switch (menuChoice(line, 1, 3)) {
                    case 1 -> {
                        servers = await(service.discoverServers(Duration.ofSeconds(2)));
                        screen = Screen.SERVERS;
                        print("SERVER SELECTION");
                        for (int i = 0; i < servers.size(); i++) {
                            ServerInfo server = servers.get(i);
                            print((i + 1) + ". " + server.name() + " " + server.host() + " TCP:" + server.gatewayPort() + " TLS:" + server.tlsPort());
                        }
                        print("Select a server number, type manual, or back.");
                    }
                    case 2 -> manual();
                    case 3 -> running = false;
                    default -> throw new IllegalArgumentException();
                }
            }
            case SERVERS -> {
                if (line.equalsIgnoreCase("back")) { screen = Screen.HOME; home(); }
                else if (line.equalsIgnoreCase("manual")) { manual(); }
                else { selected = servers.get(menuChoice(line, 1, servers.size()) - 1); authentication(); }
            }
            case AUTH -> {
                int choice = menuChoice(line, 1, 3);
                if (choice == 3) { screen = Screen.HOME; home(); return; }
                String username = read("Username:"); print("Password:");
                char[] password = input.readPassword();
                if (password == null) { running = false; return; }
                try {
                    // Allow real M5's TLS deadlines plus the subsequent TCP handshake.
                    if (choice == 2) { await(service.register(selected, username, password), 60); authentication(); }
                    else { await(service.login(selected, username, password), 60); await(service.listRooms()); }
                } finally { Arrays.fill(password, '\0'); }
            }
        }
    }

    private void manual() throws IOException {
        String configured = config.getString("client.server.host", "");
        String host = read("Server IP/hostname" + (configured.isBlank() ? ":" : " [" + configured + "]:") );
        if (host.isEmpty()) { host = configured; }
        if (host.isBlank()) { throw new IllegalArgumentException("A server hostname or IP address is required."); }
        String gateway = read("TCP game port [" + config.getInt("client.gateway.port") + "]:");
        String tls = read("TLS login port [" + config.getInt("client.tls.port") + "]:");
        selected = FormValidation.server(host,
                gateway.isBlank() ? Integer.toString(config.getInt("client.gateway.port")) : gateway,
                tls.isBlank() ? Integer.toString(config.getInt("client.tls.port")) : tls);
        authentication();
    }

    private String read(String prompt) throws IOException {
        print(prompt); String value = input.readLine();
        if (value == null) { running = false; throw new IOException("Console input ended."); }
        return value.trim();
    }
    private static <T> T await(CompletableFuture<T> future) throws ExecutionException, InterruptedException, TimeoutException {
        return await(future, 12);
    }
    private static <T> T await(CompletableFuture<T> future, long seconds) throws ExecutionException, InterruptedException, TimeoutException {
        return future.get(seconds, TimeUnit.SECONDS);
    }
    public static int answerIndex(String choice) { return menuChoice(choice, 1, 4) - 1; }
    public static int menuChoice(String text, int minimum, int maximum) {
        int value = positive(text);
        if (value < minimum || value > maximum) { throw new IllegalArgumentException("Choose a number from " + minimum + " to " + maximum + "."); }
        return value;
    }
    private static int positive(String text) {
        try {
            if (!text.matches("[0-9]+")) { throw new NumberFormatException(); }
            int value = Integer.parseInt(text); if (value < 1) { throw new NumberFormatException(); } return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a positive whole number."); }
    }

    private void home() {
        print("========================================\n          CAMPUS QUIZ BATTLE\n       Connect. Compete. Conquer.\n========================================\n1. Discover available servers\n2. Connect manually\n3. Exit");
    }
    private void authentication() {
        screen = Screen.AUTH; print("AUTHENTICATION: " + selected.name() + "\n1. Login\n2. Register\n3. Back");
    }

    private void render(Snapshot s) {
        synchronized (outputLock) {
            Snapshot old = displayed; displayed = s;
            if (old != null && s.status() == old.status() && Objects.equals(s.question(), old.question())
                    && Objects.equals(s.notice(), old.notice()) && Objects.equals(s.knownPlayers(), old.knownPlayers())
                    && Objects.equals(s.availableRooms(), old.availableRooms()) && Objects.equals(s.leaderboard(), old.leaderboard())) {
                if (!Objects.equals(s.remainingSeconds(), old.remainingSeconds()) && s.remainingSeconds() != null
                        && (s.remainingSeconds() <= 3 || s.remainingSeconds() % 5 == 0)) {
                    output.println("Time remaining (informational): " + s.remainingSeconds() + " seconds"); output.flush();
                }
                return;
            }
            output.println("\n" + s.notice());
            switch (s.status()) {
                case DISCONNECTED -> {
                    if (old != null && old.status() != GameStatus.DISCONNECTED) { screen = Screen.HOME; home(); }
                }
                case LOBBY -> {
                    output.println("GAME LOBBY");
                    s.availableRooms().forEach(room -> output.println(room.id() + "  " + room.name() + "  Players: " + room.playerCount() + "  " + room.status()));
                    output.println("1. Refresh rooms\n2. Create room\n3. Join room\n4. Logout\n5. Exit");
                }
                case WAITING_ROOM -> {
                    output.println("WAITING ROOM: " + s.roomName() + " (" + s.roomId() + ")");
                    output.println("Known players (may be incomplete): " + s.knownPlayers());
                    output.println("Host: " + s.host());
                    output.println(s.host() ? "1. Start game (or start)" : "Waiting for the host to start.");
                    output.println("Commands: leave, disconnect, exit");
                }
                case QUESTION_ACTIVE -> {
                    var q = s.question();
                    output.println("========================================\nQuestion " + q.number() + " of " + q.total()
                            + " [" + q.id() + "]\nTime remaining: " + s.remainingSeconds() + " seconds\nYour score: " + s.totalScore()
                            + "\n========================================\n" + q.text());
                    for (int i = 0; i < 4; i++) { output.println((i + 1) + ". " + q.options().get(i)); }
                    output.println("Your answer: answer " + q.id() + " <1-4>");
                }
                case ANSWER_SUBMITTED -> { /* Already printed as the state notification above. */ }
                case ROUND_RESULT -> {
                    var result = s.roundResult();
                    output.println("ROUND RESULT\nCorrect answer: " + (result.correctIndex() + 1) + ". " + s.question().options().get(result.correctIndex()));
                    output.println("Selected answer: " + (s.selectedAnswer() == null ? "No answer" : s.selectedAnswer() + 1));
                    output.println(s.selectedAnswer() == null ? "No submitted selection." : s.selectedAnswer() == result.correctIndex() ? "Correct!" : "Incorrect.");
                    output.println("Points awarded: " + result.pointsAwarded() + "\nTotal score: " + s.totalScore());
                    standings(s.leaderboard()); output.println("Waiting for the next question or final results...");
                }
                case GAME_OVER -> {
                    output.println("========================================\n           BATTLE COMPLETE\n========================================");
                    output.println("Winner: " + s.finalResult().winner()); standings(s.finalResult().standings());
                    output.println("1. Leave finished room and return to lobby\n2. Disconnect\n3. Exit");
                }
                default -> { }
            }
            output.flush();
        }
    }

    private void standings(List<PlayerScore> scores) {
        output.println("Rank    Player          Score");
        for (int i = 0; i < scores.size(); i++) {
            output.printf("%-7d %-15s %d%n", i + 1, scores.get(i).username(), scores.get(i).score());
        }
    }
    private void print(String message) { synchronized (outputLock) { output.println(message); output.flush(); } }

    @Override public void close() {
        running = false; service.state().removeObserver(observer); service.close();
        // System.in/Console are owned by the process, not by this adapter.
    }
}
