package com.quizbattle.common;

import java.io.IOException;

/**
 * Receives live countdown and leaderboard updates via UDP.
 * Implemented by M3 (UdpLiveFeedReceiver), used by M2 (client).
 */
public interface LiveFeedReceiver extends AutoCloseable {
    
    /**
     * Starts receiving UDP feed messages and notifying the listener.
     * 
     * @param listener the callback for received messages
     * @throws IOException if the receiver cannot be started
     */
    void start(LiveFeedListener listener) throws IOException;
    
    @Override
    void close();
}
