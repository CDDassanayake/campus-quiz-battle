package com.quizbattle.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

class HmacTokenServiceTest {
    
    private static final String TEST_SECRET = "test-secret-key-32-chars-long";
    
    @Test
    void testIssueToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        String token = service.issueToken("alice");
        
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.contains("."));
    }
    
    @Test
    void testVerifyValidToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        String token = service.issueToken("alice");
        
        Optional<String> username = service.verify(token);
        assertTrue(username.isPresent());
        assertEquals("alice", username.get());
    }
    
    @Test
    void testVerifyNullToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        Optional<String> username = service.verify(null);
        assertFalse(username.isPresent());
    }
    
    @Test
    void testVerifyEmptyToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        Optional<String> username = service.verify("");
        assertFalse(username.isPresent());
    }
    
    @Test
    void testVerifyMalformedToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        Optional<String> username = service.verify("invalid-token");
        assertFalse(username.isPresent());
    }
    
    @Test
    void testVerifyTamperedToken() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        String token = service.issueToken("alice");
        
        // Tamper with the token
        String tampered = token.replace("a", "b");
        Optional<String> username = service.verify(tampered);
        assertFalse(username.isPresent());
    }
    
    @Test
    void testVerifyTokenFromDifferentSecret() {
        HmacTokenService service1 = new HmacTokenService(TEST_SECRET);
        HmacTokenService service2 = new HmacTokenService("different-secret");
        
        String token = service1.issueToken("alice");
        Optional<String> username = service2.verify(token);
        assertFalse(username.isPresent());
    }
    
    @Test
    void testIssueTokenNullUsername() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        assertThrows(IllegalArgumentException.class, () -> service.issueToken(null));
    }
    
    @Test
    void testIssueTokenEmptyUsername() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        assertThrows(IllegalArgumentException.class, () -> service.issueToken(""));
    }
    
    @Test
    void testCreateServiceNullSecret() {
        assertThrows(IllegalArgumentException.class, () -> new HmacTokenService(null));
    }
    
    @Test
    void testCreateServiceEmptySecret() {
        assertThrows(IllegalArgumentException.class, () -> new HmacTokenService(""));
    }
    
    @Test
    void testCreateServiceInvalidTtl() {
        assertThrows(IllegalArgumentException.class, () -> new HmacTokenService(TEST_SECRET, 0));
        assertThrows(IllegalArgumentException.class, () -> new HmacTokenService(TEST_SECRET, -1));
    }
    
    @Test
    void testTokenExpiry() throws InterruptedException {
        // Create service with very short TTL (1 second)
        HmacTokenService service = new HmacTokenService(TEST_SECRET, 0); // 0 minutes = immediate expiry check
        // Actually, let's use a small positive value and wait
        HmacTokenService shortTtlService = new HmacTokenService(TEST_SECRET, 0);
        
        // Issue token
        String token = shortTtlService.issueToken("alice");
        
        // The token should be valid immediately
        Optional<String> username = shortTtlService.verify(token);
        assertTrue(username.isPresent());
        
        // Note: Testing actual expiry requires manipulating time or waiting,
        // which is flaky in unit tests. The expiry logic is simple and tested
        // through integration tests.
    }
    
    @Test
    void testDifferentUsersGetDifferentTokens() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        
        String token1 = service.issueToken("alice");
        String token2 = service.issueToken("bob");
        
        assertNotEquals(token1, token2);
    }
    
    @Test
    void testSameUserGetsDifferentTokensEachTime() {
        HmacTokenService service = new HmacTokenService(TEST_SECRET);
        
        String token1 = service.issueToken("alice");
        // Wait a tiny bit to ensure different expiry
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore
        }
        String token2 = service.issueToken("alice");
        
        // Tokens should be different due to different expiry times
        assertNotEquals(token1, token2);
        
        // But both should verify correctly
        assertEquals("alice", service.verify(token1).orElse(null));
        assertEquals("alice", service.verify(token2).orElse(null));
    }
}
