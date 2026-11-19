package com.quizbattle.common;

import java.io.IOException;

/**
 * Client for secure login over TLS.
 * Implemented by M5 (TlsSecureLoginClient), used by M2 (client).
 */
public interface SecureLoginClient {
    
    /**
     * Registers a new user account.
     * 
     * @param host the auth server host
     * @param tlsPort the auth server TLS port
     * @param username the username to register
     * @param password the password (will be cleared after use)
     * @throws IOException if registration fails
     */
    void register(String host, int tlsPort, String username, char[] password) throws IOException;
    
    /**
     * Logs in with existing credentials and returns a token.
     * 
     * @param host the auth server host
     * @param tlsPort the auth server TLS port
     * @param username the username
     * @param password the password (will be cleared after use)
     * @return the authentication token
     * @throws IOException if login fails
     */
    String login(String host, int tlsPort, String username, char[] password) throws IOException;
}
