package com.quizbattle.security;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Set;

/**
 * Factory for creating SSLContext for server and client TLS connections.
 * Server uses a keystore with private key; client uses a trust store with the server's certificate.
 */
public final class TlsContextFactory {
    
    private static final String[] ENABLED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    
    private TlsContextFactory() {
        // Utility class
    }
    
    /**
     * Creates an SSLContext for the server using a keystore.
     * 
     * @param keystorePath path to the PKCS12 keystore file
     * @param keystorePassword password for the keystore
     * @return configured SSLContext for server use
     * @throws IOException if the keystore cannot be read
     * @throws GeneralSecurityException if TLS setup fails
     */
    public static SSLContext createServerContext(String keystorePath, char[] keystorePassword)
            throws IOException, GeneralSecurityException {
        
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(keystorePath)) {
            keyStore.load(is, keystorePassword);
        }
        
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword);
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        
        return sslContext;
    }
    
    /**
     * Creates an SSLContext for the client using a trusted server certificate.
     * 
     * @param certPath path to the server's PEM certificate file
     * @return configured SSLContext for client use
     * @throws IOException if the certificate cannot be read
     * @throws GeneralSecurityException if TLS setup fails
     */
    public static SSLContext createClientContext(String certPath)
            throws IOException, GeneralSecurityException {
        
        // Load the server certificate
        X509Certificate serverCert = loadCertificate(certPath);
        
        // Create an in-memory trust store with just this certificate
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null); // Initialize empty
        trustStore.setCertificateEntry("quizserver", serverCert);
        
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        
        return sslContext;
    }
    
    /**
     * Loads an X509 certificate from a PEM file.
     */
    private static X509Certificate loadCertificate(String certPath)
            throws IOException, CertificateException {
        
        byte[] certBytes = Files.readAllBytes(Path.of(certPath));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
        
        if (!(cert instanceof X509Certificate)) {
            throw new CertificateException("Certificate is not X.509");
        }
        
        return (X509Certificate) cert;
    }
    
    /**
     * Configures an SSLServerSocket with secure settings.
     * Enables only TLS 1.2 and 1.3, requires client authentication is optional.
     */
    public static void configureServerSocket(SSLServerSocket serverSocket) {
        serverSocket.setEnabledProtocols(ENABLED_PROTOCOLS);
        serverSocket.setNeedClientAuth(false); // Client auth is via token, not TLS client certs
    }
    
    /**
     * Configures an SSLSocket with secure settings and hostname verification.
     * Enables only TLS 1.2 and 1.3.
     */
    public static void configureClientSocket(SSLSocket socket, String expectedHostname) {
        socket.setEnabledProtocols(ENABLED_PROTOCOLS);
        
        // Enable hostname verification
        SSLParameters params = new SSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
    }
}
