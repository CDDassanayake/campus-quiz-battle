package com.quizbattle.security;

import com.quizbattle.common.SecureLoginClient;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;

/**
 * TLS client for secure login and registration.
 * Implements SecureLoginClient interface.
 */
public final class TlsSecureLoginClient implements SecureLoginClient {
    
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    
    private final String certPath;
    
    /**
     * Creates a TLS login client.
     * 
     * @param certPath path to the server's certificate (from config)
     */
    public TlsSecureLoginClient(String certPath) {
        this.certPath = certPath;
    }
    
    @Override
    public void register(String host, int tlsPort, String username, char[] password) throws IOException {
        if (username == null || username.isEmpty() || password == null || password.length == 0) {
            throw new IllegalArgumentException("Username and password required");
        }
        
        try (SSLSocket socket = createSocket(host, tlsPort);
             BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            
            // Send REGISTER
            writer.write("REGISTER|" + username + "|" + new String(password) + "\n");
            writer.flush();
            
            // Clear password from memory
            java.util.Arrays.fill(password, '\0');
            
            // Read response
            String response = reader.readLine();
            if (response == null) {
                throw new IOException("Server closed connection");
            }
            
            if (response.startsWith("ERROR|")) {
                throw handleError(response);
            } else if (!response.equals("OK")) {
                throw new IOException("Unexpected response: " + response);
            }
            
        } catch (SocketTimeoutException e) {
            throw new IOException("Connection timed out", e);
        }
    }
    
    @Override
    public String login(String host, int tlsPort, String username, char[] password) throws IOException {
        if (username == null || username.isEmpty() || password == null || password.length == 0) {
            throw new IllegalArgumentException("Username and password required");
        }
        
        try (SSLSocket socket = createSocket(host, tlsPort);
             BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            
            // Send LOGIN
            writer.write("LOGIN|" + username + "|" + new String(password) + "\n");
            writer.flush();
            
            // Clear password from memory
            java.util.Arrays.fill(password, '\0');
            
            // Read response
            String response = reader.readLine();
            if (response == null) {
                throw new IOException("Server closed connection");
            }
            
            if (response.startsWith("ERROR|")) {
                throw handleError(response);
            } else if (response.startsWith("TOKEN|")) {
                String[] parts = response.split("\\|", 2);
                if (parts.length != 2) {
                    throw new IOException("Invalid TOKEN response format");
                }
                return parts[1];
            } else {
                throw new IOException("Unexpected response: " + response);
            }
            
        } catch (SocketTimeoutException e) {
            throw new IOException("Connection timed out", e);
        }
    }
    
    /**
     * Creates and configures an SSL socket.
     */
    private SSLSocket createSocket(String host, int port) throws IOException {
        try {
            SSLContext sslContext = TlsContextFactory.createClientContext(certPath);
            SSLSocketFactory factory = sslContext.getSocketFactory();
            
            SSLSocket socket = (SSLSocket) factory.createSocket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            TlsContextFactory.configureClientSocket(socket, host);
            
            return socket;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to create SSL context", e);
        }
    }
    
    /**
     * Handles an error response from the server.
     */
    private IOException handleError(String response) {
        String[] parts = response.split("\\|", 3);
        if (parts.length >= 2) {
            String code = parts[1];
            String text = parts.length >= 3 ? parts[2] : "";
            return new IOException("Server error: " + code + " - " + text);
        } else {
            return new IOException("Server error: " + response);
        }
    }
}
