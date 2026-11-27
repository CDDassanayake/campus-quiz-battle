package com.quizbattle.client.ui;

/** Routes for this foundation and the later presentation phases. */
public enum Screen {
    WELCOME("Welcome", false, "welcome.fxml"),
    SERVER_SELECTION("Find a battle", false, "server-selection.fxml"),
    LOGIN("Welcome back", false, "login.fxml"),
    REGISTER("Join the competition", false, "register.fxml"),
    LOBBY("Battle lobby", true, "lobby.fxml"),
    CREATE_ROOM("Create a battle", true, "create-room.fxml"),
    WAITING_ROOM("Waiting room", true, "waiting-room.fxml"),
    QUIZ("The battle is on", true, "quiz.fxml"),
    ROUND_RESULT("Round results", true, "round-result.fxml"),
    FINAL_RESULTS("Final rankings", true, "final-results.fxml"),
    SETTINGS("Your setup", false, "settings.fxml");

    private final String title;
    private final boolean authenticated;
    private final String resource;
    Screen(String title, boolean authenticated, String resource) {
        this.title = title; this.authenticated = authenticated; this.resource = resource;
    }
    public String title() { return title; }
    public boolean requiresAuthentication() { return authenticated; }
    public String resource() { return "/fxml/" + resource; }
}
