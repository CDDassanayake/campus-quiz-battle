package com.quizbattle.security;

import com.quizbattle.common.AppConfig;

import javax.net.ssl.*;
import java.io.*;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TLS authentication server for REGISTER and LOGIN.
 * Runs on tls.auth.port (default 5443).
 */
public final class TlsAuthServer implements AutoCloseable {
    
    private static final int MAX_LINE_LENGTH = 4096;
    private static final int READ_TIMEOUT_MS = 30000; // 30 seconds
    
    private SSLServerSocket serverSocket;
    private ExecutorService workerPool;
    private final UserStore userStore;
    private final HmacTokenService tokenService;
    private volatile boolean running;
    private Thread acceptThread;
    
    /**
     * Creates and starts the TLS auth server.
     * 
     * @param config the application configuration
     * @param userStore the user store for authentication
     * @param tokenService the token service for issuing tokens
     * @throws IOException if the server cannot be started
     */
    public TlsAuthServer(AppConfig config, UserStore userStore, HmacTokenService tokenService)
            throws IOException {
        this.userStore = userStore;
        this.tokenService = tokenService;
        try {
            init(config);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to initialize TLS context", e);
        }
    }
    
    private void init(AppConfig config)
            throws IOException, GeneralSecurityException {
        
        // Load keystore password from environment
        String keystorePassword = System.getenv("QB_KEYSTORE_PASSWORD");
        if (keystorePassword == null) {
            throw new IllegalStateException("QB_KEYSTORE_PASSWORD environment variable not set");
        }
        
        String keystorePath = config.getString("security.keystore.path", "secrets/keystore.p12");
        int port = config.getInt("tls.auth.port", 5443);
        
        // Create SSL context
        SSLContext sslContext = TlsContextFactory.createServerContext(
            keystorePath, keystorePassword.toCharArray());
        
        // Create server socket
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        this.serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        TlsContextFactory.configureServerSocket(this.serverSocket);
        
        // Worker pool for handling connections
        this.workerPool = Executors.newCachedThreadPool();
        
        this.running = false;
    }
    
    /**
     * Starts the server.
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        acceptThread = new Thread(this::acceptLoop, "TLS-AcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }
    
    /**
     * Stops the server.
     */
    public void stop() {
        running = false;
        
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        
        try {
            serverSocket.close();
        } catch (IOException e) {
            // Ignore
        }
        
        workerPool.shutdown();
    }
    
    /**
     * Accept loop - runs in a separate thread.
     */
    private void acceptLoop() {
        while (running && !	Thread.interrupted()) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                socket.setSoTimeout(READ_TIMEOUT_MS);
                workerPool.submit(() -> handleConnection(socket));
            } catch (SocketException e) {
                // Socket closed during stop
                if (running) {
                    System.err.println("Socket error in accept loop: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("IO error in accept loop: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Handles a client connection.
     */
    private void handleConnection(SSLSocket socket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            
            String line = reader.readLine();
            if (line == null || line.length() > MAX_LINE_LENGTH) {
                sendError(writer, "BAD_MESSAGE", "Invalid message");
                return;
            }
            
            String[] parts = line.split("\\|");
            String messageType = parts[0];
            
            switch (messageType) {
                case "REGISTER":
                    handleRegister(parts, writer);
                    break;
                case "LOGIN":
                    handleLogin(parts, writer);
                    break;
                default:
                    sendError(writer, "BAD_MESSAGE", "Unknown message type");
                    break;
            }
            
        } catch (SocketTimeoutException e) {
            // Client timed out
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Handles REGISTER message.
     */
    private void handleRegister(String[] parts, BufferedWriter writer) throws IOException {
        if (parts.length != 3) {
            sendError(writer, "BAD_MESSAGE", "Invalid REGISTER format");
            return;
        }
        
        String username = parts[1];
        String password = parts[2];
        
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            sendError(writer, "BAD_MESSAGE", "Username and password required");
            return;
        }
        
        if (userStore.register(username, password.toCharArray())) {
            writer.write("OK\n");
            writer.flush();
        } else {
            sendError(writer, "USER_EXISTS", "Username already exists");
        }
    }
    
    /**
     * Handles LOGIN message.
     */
    private void handleLogin(String[] parts, BufferedWriter writer) throws IOException {
        if (parts.length != 3) {
            sendError(writer, "BAD_MESSAGE", "Invalid LOGIN format");
            return;
        }
        
        String username = parts[1];
        String password = parts[2];
        
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            sendError(writer, "BAD_MESSAGE", "Username and password required");
            return;
        }
        
        if (userStore.verify(username, password.toCharArray())) {
            String token = tokenService.issueToken(username);
            writer.write("TOKEN|" + token + "\n");
            writer.flush();
        } else {
            sendError(writer, "BAD_CREDENTIALS", "Invalid username or password");
        }
    }
    
    /**
     * Sends an error message.
     */
    private void sendError(BufferedWriter writer, String code, String text) throws IOException {
        writer.write("ERROR|" + code + "|" + text + "\n");
        writer.flush();
    }
    
    @Override
    public void close() {
        stop();
    }
}
