package com.quizbattle.common;

import java.net.SocketAddress;
import java.util.Optional;

/**
 * Represents a client connection to the NIO gateway.
 * Implemented by M1 (NioGatewayServer).
 */
public interface ClientSession {
    
    /**
     * Returns a unique identifier for this session.
     */
    String id();
    
    /**
     * Returns the username if authenticated (after HELLO succeeded), otherwise empty.
     */
    Optional<String> username();
    
    /**
     * Returns the remote address of the client.
     */
    SocketAddress remoteAddress();
    
    /**
     * Sends a message to the client.
     * Thread-safe, never blocks (enqueues for the selector thread).
     */
    void send(Message message);
    
    /**
     * Closes the session.
     */
    void close();
}
