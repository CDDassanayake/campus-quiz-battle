package com.quizbattle.common;

import java.util.Map;

/**
 * Receives live countdown and leaderboard updates from the UDP feed.
 * Implemented by M2 (client UI), called by M3 (UdpLiveFeedReceiver).
 */
public interface LiveFeedListener {
    
    /**
     * Called when a countdown tick is received.
     * 
     * @param roomId the room identifier
     * @param secondsLeft remaining seconds in the current round
     */
    void onCountdown(String roomId, int secondsLeft);
    
    /**
     * Called when a leaderboard update is received.
     * 
     * @param roomId the room identifier
     * @param scores map of username to score
     */
    void onLeaderboard(String roomId, Map<String, Integer> scores);
}
