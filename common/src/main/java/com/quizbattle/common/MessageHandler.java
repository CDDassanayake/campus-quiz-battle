package com.quizbattle.common;

/**
 * Handles game messages from connected clients.
 * Implemented by M4 (RoomManager), called by M1 (NioGatewayServer).
 * 
 * Ordering guarantee given by M1: for ONE session, onConnect is called first (after HELLO succeeded),
 * then every onMessage in the order the client sent them (never two at the same time),
 * then onDisconnect. Different sessions run in parallel on the handler pool,
 * so M4 must make room/score state thread-safe.
 */
public interface MessageHandler {
    
    /**
     * Called when a client successfully authenticates with HELLO.
     * The session's username() is always present at this point.
     * 
     * @param session the authenticated client session
     */
    void onConnect(ClientSession session);
    
    /**
     * Called when a message is received from an authenticated client.
     * Never called for HELLO or PING (the gateway handles those).
     * 
     * @param session the client session
     * @param message the received message
     */
    void onMessage(ClientSession session, Message message);
    
    /**
     * Called when a client disconnects (either gracefully or due to error).
     * 
     * @param session the client session
     */
    void onDisconnect(ClientSession session);
}
