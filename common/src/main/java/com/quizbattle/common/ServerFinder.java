package com.quizbattle.common;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Finds Quiz Battle servers on the LAN via UDP broadcast.
 * Implemented by M3 (UdpServerFinder), used by M2 (client).
 */
public interface ServerFinder {
    
    /**
     * Discovers servers on the LAN by broadcasting a discovery request.
     * 
     * @param timeout how long to wait for responses
     * @return list of discovered servers (may be empty)
     * @throws IOException if the discovery cannot be performed
     */
    List<ServerInfo> find(Duration timeout) throws IOException;
}
