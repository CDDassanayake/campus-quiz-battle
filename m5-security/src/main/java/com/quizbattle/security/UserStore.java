package com.quizbattle.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * In-memory user store with salted password hashes.
 * Passwords are never stored in plain text.
 */
public final class UserStore {
    
    private static final int SALT_LENGTH = 16;
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    
    /**
     * Represents a stored user with salted password hash.
     */
    private static record UserRecord(String username, byte[] salt, byte[] hash) {
        // Salt and hash are stored as byte arrays
    }
    
    public UserStore() {
        // Empty constructor
    }
    
    /**
     * Registers a new user with a password.
     * 
     * @param username the username
     * @param password the password (will be cleared after use)
     * @return true if registration succeeded, false if user already exists
     */
    public boolean register(String username, char[] password) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        if (password == null || password.length == 0) {
            return false;
        }
        
        // Check if user already exists
        if (users.containsKey(username)) {
            return false;
        }
        
        // Generate salt
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        
        // Hash the password
        byte[] hash = hashPassword(password, salt);
        
        // Store the user record
        users.put(username, new UserRecord(username, salt, hash));
        
        // Clear the password from memory
        java.util.Arrays.fill(password, '\0');
        
        return true;
    }
    
    /**
     * Verifies a user's password.
     * 
     * @param username the username
     * @param password the password to verify (will be cleared after use)
     * @return true if the password is correct
     */
    public boolean verify(String username, char[] password) {
        UserRecord record = users.get(username);
        if (record == null) {
            return false;
        }
        
        byte[] computedHash = hashPassword(password, record.salt());
        
        // Use constant-time comparison to prevent timing attacks
        boolean match = MessageDigest.isEqual(record.hash(), computedHash);
        
        // Clear the password from memory
        java.util.Arrays.fill(password, '\0');
        
        return match;
    }
    
    /**
     * Checks if a user exists.
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
    
    /**
     * Hashes a password with PBKDF2 and a salt.
     */
    private byte[] hashPassword(char[] password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
    
    /**
     * Clears all users from the store (for testing).
     */
    public void clear() {
        users.clear();
    }
    
    /**
     * Returns the number of users in the store.
     */
    public int size() {
        return users.size();
    }
}
