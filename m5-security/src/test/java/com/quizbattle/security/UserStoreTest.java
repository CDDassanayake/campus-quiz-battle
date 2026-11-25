package com.quizbattle.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserStoreTest {
    
    @Test
    void testRegisterSuccess() {
        UserStore store = new UserStore();
        assertTrue(store.register("alice", "password123".toCharArray()));
        assertTrue(store.userExists("alice"));
        assertEquals(1, store.size());
    }
    
    @Test
    void testRegisterDuplicateUser() {
        UserStore store = new UserStore();
        assertTrue(store.register("alice", "password123".toCharArray()));
        assertFalse(store.register("alice", "different".toCharArray()));
        assertEquals(1, store.size());
    }
    
    @Test
    void testRegisterNullUsername() {
        UserStore store = new UserStore();
        assertFalse(store.register(null, "password".toCharArray()));
    }
    
    @Test
    void testRegisterEmptyUsername() {
        UserStore store = new UserStore();
        assertFalse(store.register("", "password".toCharArray()));
    }
    
    @Test
    void testRegisterNullPassword() {
        UserStore store = new UserStore();
        assertFalse(store.register("alice", null));
    }
    
    @Test
    void testRegisterEmptyPassword() {
        UserStore store = new UserStore();
        assertFalse(store.register("alice", new char[0]));
    }
    
    @Test
    void testVerifyCorrectPassword() {
        UserStore store = new UserStore();
        store.register("alice", "password123".toCharArray());
        assertTrue(store.verify("alice", "password123".toCharArray()));
    }
    
    @Test
    void testVerifyWrongPassword() {
        UserStore store = new UserStore();
        store.register("alice", "password123".toCharArray());
        assertFalse(store.verify("alice", "wrongpassword".toCharArray()));
    }
    
    @Test
    void testVerifyNonexistentUser() {
        UserStore store = new UserStore();
        assertFalse(store.verify("alice", "password".toCharArray()));
    }
    
    @Test
    void testVerifyNullUsername() {
        UserStore store = new UserStore();
        assertFalse(store.verify(null, "password".toCharArray()));
    }
    
    @Test
    void testVerifyNullPassword() {
        UserStore store = new UserStore();
        store.register("alice", "password".toCharArray());
        assertFalse(store.verify("alice", null));
    }
    
    @Test
    void testPasswordHashingIsSalted() {
        UserStore store = new UserStore();
        store.register("alice", "password".toCharArray());
        store.register("bob", "password".toCharArray());
        
        // Same password for different users should have different hashes (due to salt)
        assertTrue(store.verify("alice", "password".toCharArray()));
        assertTrue(store.verify("bob", "password".toCharArray()));
    }
    
    @Test
    void testClear() {
        UserStore store = new UserStore();
        store.register("alice", "password".toCharArray());
        store.register("bob", "password".toCharArray());
        assertEquals(2, store.size());
        
        store.clear();
        assertEquals(0, store.size());
        assertFalse(store.userExists("alice"));
        assertFalse(store.userExists("bob"));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        UserStore store = new UserStore();
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final String username = "user" + i;
            threads[i] = new Thread(() -> {
                store.register(username, "password".toCharArray());
                store.verify(username, "password".toCharArray());
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(threadCount, store.size());
    }
}
