package com.quizbattle.client;

import com.quizbattle.common.LiveFeedListener;
import com.quizbattle.common.LiveFeedReceiver;
import java.io.IOException;
import java.util.Map;

/** Direct callback fixture; does not send UDP packets. */
public final class FakeLiveFeedReceiver implements LiveFeedReceiver {
    public volatile LiveFeedListener listener;
    public volatile boolean closed;
    public volatile boolean failStart;
    @Override public void start(LiveFeedListener listener) throws IOException {
        if (failStart) { throw new IOException("Synthetic live-feed failure"); }
        this.listener = listener;
    }
    public void countdown(String room, int seconds) { listener.onCountdown(room, seconds); }
    public void leaderboard(String room, Map<String, Integer> scores) { listener.onLeaderboard(room, scores); }
    @Override public void close() { closed = true; }
}
