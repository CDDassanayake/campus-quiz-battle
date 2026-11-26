package com.quizbattle.security;

import com.quizbattle.common.TokenVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * HMAC-signed token service for session authentication.
 * Token format: base64url(username).expiryEpochSeconds.base64url(HMAC-SHA256(secret, username + "." + expiry))
 * Implements TokenVerifier for use by the NIO gateway.
 */
public final class HmacTokenService implements TokenVerifier {
    
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_TTL_MINUTES = 60;
    
    private final byte[] secretKey;
    private final long ttlMinutes;
    
    /**
     * Creates a token service with the given secret key.
     * 
     * @param secretKey the secret key for signing tokens (from QB_TOKEN_SECRET env var)
     */
    public HmacTokenService(String secretKey) {
        this(secretKey, DEFAULT_TTL_MINUTES);
    }
    
    /**
     * Creates a token service with the given secret key and TTL.
     * 
     * @param secretKey the secret key for signing tokens
     * @param ttlMinutes token time-to-live in minutes
     */
    public HmacTokenService(String secretKey, long ttlMinutes) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("Secret key cannot be null or empty");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
        this.ttlMinutes = ttlMinutes;
    }
    
    /**
     * Issues a token for the given username.
     * 
     * @param username the username
     * @return the signed token string
     */
    public String issueToken(String username) {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        
        long expiry = Instant.now().getEpochSecond() + (ttlMinutes * 60);
        String payload = username + "." + expiry;
        String signature = hmacSha256(payload);
        
        String encodedUsername = base64UrlEncode(username);
        String encodedExpiry = base64UrlEncode(String.valueOf(expiry));
        String encodedSignature = base64UrlEncode(signature);
        
        return encodedUsername + "." + encodedExpiry + "." + encodedSignature;
    }
    
    /**
     * Verifies a token and returns the username if valid and not expired.
     * 
     * @param token the token string to verify
     * @return the username if valid, otherwise empty
     */
    @Override
    public Optional<String> verify(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        
        try {
            String username = base64UrlDecode(parts[0]);
            String expiryStr = base64UrlDecode(parts[1]);
            String providedSignature = base64UrlDecode(parts[2]);
            
            // Check expiry
            long expiry = Long.parseLong(expiryStr);
            long now = Instant.now().getEpochSecond();
            if (now >= expiry) {
                return Optional.empty(); // Token expired
            }
            
            // Verify signature
            String payload = username + "." + expiryStr;
            String expectedSignature = hmacSha256(payload);
            
            // Constant-time comparison
            if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty(); // Invalid signature
            }
            
            return Optional.of(username);
        } catch (Exception e) {
            return Optional.empty(); // Token malformed
        }
    }
    
    /**
     * Computes HMAC-SHA256 of the given data.
     */
    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
    
    /**
     * Base64URL-encodes a string.
     */
    private String base64UrlEncode(String data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Base64URL-decodes a string.
     */
    private String base64UrlDecode(String data) {
        byte[] bytes = Base64.getUrlDecoder().decode(data);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
