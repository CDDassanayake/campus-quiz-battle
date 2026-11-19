package com.quizbattle.common;

/**
 * Exception thrown when a protocol message is malformed or violates protocol rules.
 */
public class ProtocolException extends RuntimeException {
    
    public ProtocolException(String message) {
        super(message);
    }
    
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
