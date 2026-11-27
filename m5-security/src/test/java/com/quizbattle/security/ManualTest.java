package com.quizbattle.security;

/**
 * Manual test runner for M5 security components.
 * Run with: javac -cp ../target/classes ManualTest.java && java -cp .;../target/classes ManualTest
 */
public class ManualTest {
    
    public static void main(String[] args) {
        System.out.println("=== M5 Security Manual Tests ===\n");
        
        testUserStore();
        testHmacTokenService();
        
        System.out.println("\n=== All tests completed ===");
    }
    
    private static void testUserStore() {
        System.out.println("Testing UserStore...");
        UserStore store = new UserStore();
        
        // Test register
        boolean reg1 = store.register("alice", "password123".toCharArray());
        boolean reg2 = store.register("bob", "password456".toCharArray());
        boolean reg3 = store.register("alice", "different".toCharArray()); // duplicate
        
        assertTrue(reg1, "Register alice should succeed");
        assertTrue(reg2, "Register bob should succeed");
        assertTrue(!reg3, "Register duplicate alice should fail");
        assertTrue(store.userExists("alice"), "Alice should exist");
        assertTrue(store.userExists("bob"), "Bob should exist");
        assertTrue(store.size() == 2, "Should have 2 users");
        
        // Test verify
        boolean v1 = store.verify("alice", "password123".toCharArray());
        boolean v2 = store.verify("alice", "wrong".toCharArray());
        boolean v3 = store.verify("nonexistent", "password".toCharArray());
        
        assertTrue(v1, "Correct password should verify");
        assertTrue(!v2, "Wrong password should not verify");
        assertTrue(!v3, "Nonexistent user should not verify");
        
        System.out.println("✓ UserStore tests passed");
    }
    
    private static void testHmacTokenService() {
        System.out.println("Testing HmacTokenService...");
        String secret = "test-secret-key-32-chars-long";
        HmacTokenService service = new HmacTokenService(secret);
        
        // Test issue token
        String token = service.issueToken("alice");
        assertTrue(token != null && !token.isEmpty(), "Token should not be null or empty");
        assertTrue(token.contains("."), "Token should contain dots");
        
        // Test verify valid token
        var username = service.verify(token);
        assertTrue(username.isPresent(), "Valid token should verify");
        assertTrue("alice".equals(username.get()), "Username should be alice");
        
        // Test verify null token
        var nullResult = service.verify(null);
        assertTrue(!nullResult.isPresent(), "Null token should not verify");
        
        // Test verify empty token
        var emptyResult = service.verify("");
        assertTrue(!emptyResult.isPresent(), "Empty token should not verify");
        
        // Test verify malformed token
        var malformedResult = service.verify("invalid-token");
        assertTrue(!malformedResult.isPresent(), "Malformed token should not verify");
        
        // Test verify tampered token (change first character of token)
        String tampered = "X" + token.substring(1);
        var tamperedResult = service.verify(tampered);
        assertTrue(!tamperedResult.isPresent(), "Tampered token should not verify");
        
        // Test verify token from different secret
        HmacTokenService service2 = new HmacTokenService("different-secret");
        var wrongSecretResult = service2.verify(token);
        assertTrue(!wrongSecretResult.isPresent(), "Token from different secret should not verify");
        
        System.out.println("✓ HmacTokenService tests passed");
    }
    
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }
}
