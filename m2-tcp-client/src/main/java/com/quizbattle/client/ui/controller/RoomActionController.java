package com.quizbattle.client.ui.controller;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.ui.AsyncViewScope;
import com.quizbattle.client.ui.RoomReply;
import com.quizbattle.client.ui.SceneManager;
import com.quizbattle.client.ui.UiContext;
import com.quizbattle.client.ui.UiErrors;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;

abstract class RoomActionController implements ViewController {
    protected final UiContext context; protected final SceneManager scenes;
    protected AsyncViewScope tasks;
    private RoomReply reply;
    protected RoomActionController(UiContext context, SceneManager scenes) { this.context = context; this.scenes = scenes; }
    protected void configureActions(Consumer<Boolean> busy) { tasks = new AsyncViewScope(Platform::runLater, busy); }
    protected void awaitReply(RoomReply.Kind kind, Supplier<CompletableFuture<Void>> command, Consumer<String> error) {
        if (tasks.busy()) { return; }
        reply = new RoomReply(kind, context.state().snapshot().version());
        RoomReply pending = reply;
        tasks.run(() -> context.invoke(command).thenCompose(ignored -> pending.future()), Duration.ofSeconds(8), ignored -> { },
                failure -> error.accept(UiErrors.message(failure, "The request could not be completed. Check the connection and retry.")));
    }
    @Override public void update(Snapshot snapshot) { if (reply != null) { reply.observe(snapshot); } }
    @Override public void close() {
        tasks.close(); if (reply != null) { reply.future().cancel(false); }
    }
}
