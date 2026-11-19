package com.quizbattle.common;

import java.io.IOException;

/**
 * Announces the server's presence on the LAN via UDP broadcast.
 * Implemented by M3 (UdpServerAnnouncer), used by app (ServerMain).
 */
public interface ServerAnnouncer extends AutoCloseable {
    
    /**
     * Starts announcing the server on the discovery port.
     * 
     * @param info the server information to announce
     * @throws IOException if the announcer cannot be started
     */
    void start(ServerInfo info) throws IOException;
    
    @Override
    void close();
}
