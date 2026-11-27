package com.quizbattle.client.ui;

import javafx.application.Application;

/** Separate entry point keeps the standard Java module-path launcher explicit. */
public final class GuiLauncher {
    private GuiLauncher() { }
    public static void main(String[] args) { Application.launch(ClientApplication.class, args); ClientApplication.awaitShutdown(); }
}
