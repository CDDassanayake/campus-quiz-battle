package com.quizbattle.client.ui;

import com.quizbattle.client.model.ClientGameState.Snapshot;
import com.quizbattle.client.model.GameStatus;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Waits for authoritative JOINED/QUESTION state, not merely a successful socket write. */
public final class RoomReply {
    public enum Kind { JOINED, QUESTION }
    private final Kind kind;
    private final long afterVersion;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    public RoomReply(Kind kind, long afterVersion) { this.kind = kind; this.afterVersion = afterVersion; }
    public CompletableFuture<Void> future() { return result; }
    public void observe(Snapshot snapshot) {
        if (result.isDone() || snapshot.version() <= afterVersion) { return; }
        if (!snapshot.authenticated()) { result.completeExceptionally(new IOException("Connection closed while waiting for the server.")); }
        else if (snapshot.errorCode() != null) { result.completeExceptionally(new IOException(snapshot.notice())); }
        else if (kind == Kind.JOINED && snapshot.roomId() != null && snapshot.status() == GameStatus.WAITING_ROOM) { result.complete(null); }
        else if (kind == Kind.QUESTION && snapshot.question() != null && snapshot.status() == GameStatus.QUESTION_ACTIVE) { result.complete(null); }
    }
}
