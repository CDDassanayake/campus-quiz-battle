package com.quizbattle.common;

import java.util.Map;

/**
 * Publishes live countdown and leaderboard updates via UDP.
 * Implemented by M3 (UdpLiveFeedPublisher), called by M4 (RoomManager).
 */
public interface LiveFeedPublisher extends AutoCloseable {
    
    /**
     * Publishes a countdown tick for a room.
     * 
     * @param roomId the room identifier
     * @param secondsLeft remaining seconds in the current round
     */
    void publishCountdown(String roomId, int secondsLeft);
    
    /**
     * Publishes the current leaderboard for a room.
     * 
     * @param roomId the room identifier
     * @param scores map of username to score
     */
    void publishLeaderboard(String roomId, Map<String, Integer> scores);
    
    @Override
    void close();
}
