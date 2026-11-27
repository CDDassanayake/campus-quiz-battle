package com.quizbattle.client;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.*;
import com.quizbattle.common.ServerInfo;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import java.util.concurrent.*;
import java.util.function.*;

/** Opt-in real JavaFX interaction against the three-round TCP fixture. */
final class GuiGameplaySmoke implements AutoCloseable {
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private final Stage stage;
    private final UiContext context;
    private final GameClientService service;
    private final GuiMockScenario scenario;
    private final ServerInfo info;
    private final BiConsumer<Boolean, String> check;
    private final GuiJourneySmoke.Capture capture;
    private final Consumer<Throwable> failure;
    private final Runnable finished;
    private final SceneManager scenes;
    private PauseTransition pulse;
    private boolean closed;
    private int baseline;
    private Label retiredFreshness;
    private String retiredFreshnessText;
    private Label retiredStatus;
    GuiGameplaySmoke(Stage stage, UiContext context, GameClientService service, GuiMockScenario scenario, ServerInfo info,
                     BiConsumer<Boolean, String> check, GuiJourneySmoke.Capture capture, Consumer<Throwable> failure, Runnable finished) throws Exception {
        this.stage = stage; this.context = context; this.service = service; this.scenario = scenario; this.info = info;
        this.check = check; this.capture = capture; this.failure = failure; this.finished = finished;
        scenario.fullGame = true; scenario.automatic = false; scenario.receivedAnswers.clear();
        scenario.welcomeGate = null; scenario.joinedGate = null; scenario.questionGate = null;
        scenes = new SceneManager(stage, context); Scene scene = new Scene(scenes.root(), 1280, 800);
        scene.getStylesheets().addAll(SceneManager.stylesheets()); stage.setScene(scene); stage.setWidth(1296); stage.setHeight(839);
    }
    void start() {
        context.selectServer(info); context.beginAuthentication();
        context.invoke(() -> service.login(info, "alice", new char[]{'x'})).whenComplete((v, e) -> Platform.runLater(() -> run(() -> {
            if (e != null) { throw new AssertionError(e); } await(() -> has("createBattle") && !button("createBattle").isDisabled(), this::create);
        })));
    }
    private void create() {
        click("createBattle"); text("roomName", "Gameplay Champions"); text("questionCount", "3"); click("createRoomButton");
        await(() -> has("startBattle"), () -> { click("startBattle"); await(() -> has("answer1"), this::arena); });
    }
    private void arena() throws Exception {
        check.accept(label("questionNumber").equals("QUESTION 1 / 3"), "Gameplay: actual question progress renders");
        check.accept(label("questionText").contains("reliable"), "Gameplay: actual TCP question renders");
        check.accept(label("countdownSeconds").equals("15"), "Gameplay: countdown initializes from QUESTION"); save("quiz-active.png");
        baseline = scenario.answers.get(); scenes.help();
        javafx.stage.Window guide = javafx.stage.Window.getWindows().stream().filter(w -> w != stage && w.isShowing()).findFirst().orElseThrow();
        guide.getScene().getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DIGIT2, false, false, false, false));
        check.accept(scenario.answers.get() == baseline, "Gameplay: modal help prevents answer shortcuts");
        scenes.closeHelp(); key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT2);
        check.accept(!service.state().snapshot().answerSubmitted(), "Gameplay: a key held in the dialog cannot answer immediately after it closes");
        key(KeyEvent.KEY_RELEASED, KeyCode.DIGIT2);
        click("leavePlaceholder"); key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT3);
        check.accept(!scenes.gameplayInputAllowed() && service.state().snapshot().roomId() != null, "Gameplay: leave confirmation keeps the room and blocks answer shortcuts");
        scenes.closeLeaveConfirmation(); key(KeyEvent.KEY_RELEASED, KeyCode.DIGIT3);
        check.accept(service.state().snapshot().roomId() != null, "Gameplay: cancelling Leave preserves the active question");
        scenario.feed.countdown("wrong-room", 1); scenario.feed.countdown("r5", 5);
        scenario.feed.leaderboard("r5", java.util.Map.of("bob", 250, "alice", 0, "charlie", 75));
        await(() -> label("countdownSeconds").equals("5") && has(".ranking-self"), this::rankings);
    }
    private void rankings() throws Exception {
        check.accept(!button("answer1").isDisabled(), "Gameplay: live countdown does not block answering");
        check.accept(scenes.root().lookup(".ranking-self") != null, "Gameplay: live leaderboard highlights authenticated player"); save("quiz-live-leaderboard.png");
        await(() -> label("rankingFreshness").contains("No recent"), () -> {
            check.accept(label("rankingFreshness").contains("No recent"), "Gameplay: an idle UDP ranking visibly becomes stale"); save("quiz-stale-rankings.png");
            stage.setWidth(960); stage.setHeight(640); later(this::minimum);
        });
    }
    private void minimum() throws Exception {
        save("quiz-minimum.png"); var card = button("answer3");
        check.accept(card.localToScene(card.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth(), "Gameplay: answer grid fits minimum width");
        ScrollPane scroll = (ScrollPane) scenes.root().lookup(".page-scroll"); scroll.setVvalue(1); layout();
        check.accept(card.localToScene(card.getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Gameplay: all answers remain reachable at minimum height");
        stage.setWidth(1296); stage.setHeight(839); scroll.setVvalue(0); stage.requestFocus(); later(this::mouseAnswer);
    }
    private void mouseAnswer() throws Exception {
        // fire is JavaFX's accessible button activation, following a real mouse press token.
        Button card = button("answer1"); card.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
        card.fire();
        check.accept(label("answerStatus").startsWith("Sending"), "Gameplay: immediate sending presentation before asynchronous write");
        save("quiz-selected-answer.png"); card.fire(); key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT1);
        await(() -> service.state().snapshot().answerSubmitted() && scenario.receivedAnswers.size() == 1 && label("answerStatus").startsWith("Answer sent"), this::sent);
    }
    private void sent() throws Exception {
        check.accept(scenario.answers.get() == baseline + 1 && service.state().snapshot().selectedAnswer() == 1, "Gameplay: mouse maps to index 1 and duplicates are suppressed");
        check.accept(service.state().snapshot().totalScore() == 0 && service.state().snapshot().roundResult() == null, "Gameplay: sent answer never fabricates acceptance, points or a result");
        retiredFreshness = (Label) scenes.root().lookup("#rankingFreshness"); retiredStatus = (Label) scenes.root().lookup("#answerStatus");
        save("quiz-answer-submitted.png"); context.reducedMotionProperty().set(false);
        server(scenario::finishRound, () -> await(() -> has("resultHeading"), () -> {
            context.reducedMotionProperty().set(true);
            check.accept(label("awardedPoints").equals("+100 pts"), "Gameplay: reduced motion settles an active server-points animation"); correct();
        }));
    }
    private void correct() throws Exception {
        retiredFreshnessText = retiredFreshness.getText();
        check.accept(label("resultHeading").equals("Correct selection") && label("awardedPoints").equals("+100 pts"), "Gameplay: correct RESULT shows actual awarded points"); save("round-correct.png");
        // Keep DIGIT1 held across the next QUESTION. It must not answer the replacement question.
        server(scenario::nextQuestion, () -> await(() -> scenes.currentScreen() == Screen.QUIZ && label("questionNumber").equals("QUESTION 2 / 3"), this::second));
    }
    private void second() throws Exception {
        baseline = scenario.answers.get(); key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT1);
        check.accept(!service.state().snapshot().answerSubmitted(), "Gameplay: held key cannot answer a new question");
        check.accept(label("questionText").contains("distributed quiz"), "Gameplay: long question is retained");
        stage.setWidth(960); stage.setHeight(640); later(() -> {
            save("quiz-long-answers-minimum.png"); check.accept(button("answer2").getGraphic() != null, "Gameplay: long answer cards render at minimum size");
            stage.setWidth(1296); stage.setHeight(839); stage.requestFocus(); key(KeyEvent.KEY_RELEASED, KeyCode.DIGIT1); later(this::keyboardAnswer);
        });
    }
    private void keyboardAnswer() {
        key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT1); key(KeyEvent.KEY_PRESSED, KeyCode.DIGIT1);
        await(() -> scenario.answers.get() == baseline + 1 && service.state().snapshot().answerSubmitted(), () -> {
            key(KeyEvent.KEY_RELEASED, KeyCode.DIGIT1);
            check.accept(service.state().snapshot().selectedAnswer() == 0, "Gameplay: keyboard 1 submits zero-based index 0 once");
            server(scenario::finishRound, () -> await(() -> has("resultHeading"), this::incorrect));
        });
    }
    private void incorrect() throws Exception {
        check.accept(label("resultHeading").equals("Incorrect selection") && label("awardedPoints").equals("+7 pts") && label("totalScore").equals("107 total points"), "Gameplay: incorrect answer preserves nonzero server award and total");
        save("round-incorrect.png"); key(KeyEvent.KEY_RELEASED, KeyCode.DIGIT1);
        scenario.missingFeed = true; server(scenario::nextQuestion, () -> await(() -> scenes.currentScreen() == Screen.QUIZ && label("questionNumber").equals("QUESTION 3 / 3"), this::unanswered));
    }
    private void unanswered() {
        check.accept(label("countdownSeconds").equals("15"), "Gameplay: missing feed leaves honest initial time for a new question");
        scenario.feed.countdown("r5", 0);
        await(() -> label("countdownSeconds").equals("0"), () -> {
            check.accept(!button("answer0").isDisabled() && service.state().snapshot().roundResult() == null, "Gameplay: zero countdown cannot end a round or reject an answer locally");
            server(scenario::finishRound, () -> await(() -> has("resultHeading"), () -> {
                check.accept(label("resultHeading").equals("Unanswered"), "Gameplay: no local answer renders unanswered RESULT"); save("round-unanswered.png");
                server(scenario::nextQuestion, () -> await(() -> has("winnerPodium"), this::finals));
            }));
        });
    }
    private void finals() throws Exception {
        check.accept(label("winnerName").equals("bob wins the battle!"), "Gameplay: TCP winner is authoritative");
        check.accept(service.state().snapshot().finalResult().standings().size() == 4 && label("personalResult").contains("107"), "Gameplay: complete standings and personal final score render");
        save("final-winner-podium.png"); ScrollPane scroll = (ScrollPane) scenes.root().lookup(".page-scroll"); scroll.setVvalue(1); layout(); save("final-complete-standings.png");
        scenario.feed.leaderboard("r5", java.util.Map.of("eve", 9999));
        context.reducedMotionProperty().set(false); stage.setFullScreen(true);
        check.accept(stage.isFullScreen(), "Gameplay: final podium supports fullscreen"); stage.setFullScreen(false); context.reducedMotionProperty().set(true);
        finalVariants(0);
    }
    private void finalVariants(int index) {
        String[] payloads = {"bob:30", "bob:30;alice:30", "bob:30;alice:30;charlie:30", ""};
        if (index == payloads.length) {
            stage.setWidth(960); stage.setHeight(640); later(() -> {
                save("final-minimum.png"); var scroll = (ScrollPane) scenes.root().lookup(".page-scroll"); scroll.setVvalue(1); layout();
                check.accept(button("returnToLobby").localToScene(button("returnToLobby").getBoundsInLocal()).getMaxY() <= stage.getScene().getHeight(), "Gameplay: final actions remain reachable at minimum size");
                stage.setWidth(1296); stage.setHeight(839); click("returnToLobby");
                await(() -> has("createBattle") && !button("createBattle").isDisabled() && service.state().snapshot().roomId() == null, this::returned);
            }); return;
        }
        String winner = index == 3 ? "" : index == 2 ? "charlie" : "bob";
        server(() -> scenario.finalFixture(winner, payloads[index]), () -> await(() ->
                service.state().snapshot().finalResult().winner().equals(winner) && label("winnerName").equals(winner.isEmpty() ? "No winner supplied" : winner + " wins the battle!")
                        && ((javafx.scene.layout.HBox) scenes.root().lookup("#winnerPodium")).getChildren().size() == (index == 3 ? 0 : index + 1), () -> {
            var podium = (javafx.scene.layout.HBox) scenes.root().lookup("#winnerPodium");
            check.accept(podium.getChildren().size() == (index == 3 ? 0 : index + 1), "Gameplay: podium renders " + (index == 3 ? "empty" : index + 1 + " player") + " final data");
            if (index == 0) { check.accept(label("personalResult").contains("not listed"), "Gameplay: missing self has no fabricated final score"); }
            if (index == 2) { check.accept(label("winnerName").startsWith("charlie"), "Gameplay: tied server winner is preserved despite listing position three"); }
            save("final-variant-" + index + ".png"); finalVariants(index + 1);
        }));
    }
    private void returned() {
        check.accept(service.state().snapshot().authenticated() && service.state().snapshot().finalResult() == null, "Gameplay: Return to Lobby sends LEAVE and clears finished game while preserving authentication");
        await(() -> has("join-r1"), () -> {
            check.accept(service.state().snapshot().availableRooms().size() == 4, "Gameplay: lobby refresh receives actual ROOMS");
            scenario.missingFeed = false; click("createBattle"); text("roomName", "Disconnect Test"); text("questionCount", "3"); click("createRoomButton");
            await(() -> has("startBattle"), () -> { click("startBattle"); await(() -> has("answer0"), this::rejected); });
        });
    }
    private void rejected() {
        scenario.nextAnswerError = "TOO_LATE"; click("answer0");
        await(() -> label("answerStatus").contains("TOO_LATE"), () -> {
            check.accept(service.state().snapshot().authenticated() && button("answer0").isDisabled(), "Gameplay: TOO_LATE rejection remains authenticated and locks round input"); save("quiz-rejected.png");
            server(scenario::nextQuestion, () -> await(() -> has("answer0") && !button("answer0").isDisabled(), () -> {
                scenario.nextAnswerError = "ALREADY_ANSWERED"; click("answer0");
                await(() -> label("answerStatus").contains("ALREADY_ANSWERED"), () -> {
                    check.accept(button("answer0").isDisabled() && service.state().snapshot().authenticated(), "Gameplay: ALREADY_ANSWERED rejection preserves the authenticated waiting state");
                    Button retired = button("answer0"); int before = scenario.answers.get();
                    server(scenario::rapidTransition, () -> await(() -> scenes.currentScreen() == Screen.QUIZ && label("questionText").contains("rapid server-controlled"), () -> {
                        check.accept(label("playerScore").startsWith("123 pts") && !button("answer0").isDisabled(), "Gameplay: rapid RESULT then QUESTION immediately replaces the round while preserving server total");
                        retired.fire(); check.accept(scenario.answers.get() == before && !service.state().snapshot().answerSubmitted(), "Gameplay: interactions from a disposed question cannot submit to its replacement");
                        server(scenario::disconnectServer, () -> await(() -> scenes.currentScreen() == Screen.LOGIN, this::lost));
                    }));
                });
            }));
        });
    }
    private void lost() throws Exception {
        check.accept(!service.state().snapshot().authenticated() && service.state().snapshot().question() == null, "Gameplay: connection loss clears gameplay and routes to recovery"); save("gameplay-connection-lost.png");
        check.accept(scenes.notifications().isVisible(), "Gameplay: connection loss presents a clear notification");
        pulse = new PauseTransition(javafx.util.Duration.millis(1200)); pulse.setOnFinished(e -> run(() -> {
            check.accept(retiredFreshness.getText().equals(retiredFreshnessText), "Gameplay: disposed quiz stops its ranking freshness timeline");
            check.accept(retiredStatus.getOpacity() == 1, "Gameplay: disposed quiz stops and settles its waiting animation");
            close(); finished.run();
        })); pulse.play();
    }
    private void key(javafx.event.EventType<KeyEvent> type, KeyCode code) { scenes.root().fireEvent(new KeyEvent(type, "", "", code, false, false, false, false)); }
    private Button button(String id) { return (Button) scenes.root().lookup("#" + id); }
    private boolean has(String id) { return scenes.root().lookup(id.startsWith(".") ? id : "#" + id) != null; }
    private String label(String id) { var node = (Label) scenes.root().lookup("#" + id); return node == null ? "" : node.getText(); }
    private void click(String id) { layout(); if (button(id) == null || button(id).isDisabled()) { throw new AssertionError("Unavailable action: " + id); } button(id).fire(); layout(); }
    private void text(String id, String value) { ((TextField) scenes.root().lookup("#" + id)).setText(value); }
    private void layout() { scenes.root().applyCss(); scenes.root().layout(); }
    private void save(String name) throws Exception { capture.save(scenes.root(), name); }
    private void server(Step operation, Step next) {
        CompletableFuture.runAsync(() -> { try { operation.run(); } catch (Exception e) { throw new CompletionException(e); } })
                .whenComplete((v, e) -> Platform.runLater(() -> run(() -> { if (e != null) { throw new AssertionError(e); } next.run(); })));
    }
    private void await(BooleanSupplier condition, Step next) { poll(condition, next, System.nanoTime() + TimeUnit.SECONDS.toNanos(6)); }
    private void poll(BooleanSupplier condition, Step next, long deadline) {
        if (closed) { return; } run(() -> { layout(); if (condition.getAsBoolean()) { next.run(); return; }
            if (System.nanoTime() > deadline) { throw new AssertionError("Gameplay timed out: " + scenes.currentScreen() + " / " + service.state().snapshot().notice()); }
            pulse = new PauseTransition(javafx.util.Duration.millis(40)); pulse.setOnFinished(e -> poll(condition, next, deadline)); pulse.play(); });
    }
    private void later(Step step) { pulse = new PauseTransition(javafx.util.Duration.millis(240)); pulse.setOnFinished(e -> run(step)); pulse.play(); }
    private void run(Step step) { if (closed) { return; } try { step.run(); } catch (Throwable e) { close(); failure.accept(e); } }
    @Override public void close() { if (closed) { return; } closed = true; if (pulse != null) { pulse.stop(); } scenes.close(); }
}
