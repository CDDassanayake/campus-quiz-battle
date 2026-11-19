package com.quizbattle.common;

import java.util.Optional;

/**
 * Verifies HMAC-signed tokens issued by the auth server.
 * Implemented by M5 (HmacTokenService), called by M1 (NioGatewayServer).
 */
public interface TokenVerifier {
    
    /**
     * Verifies a token and returns the username if valid and not expired.
     * 
     * @param token the token string to verify
     * @return the username if valid, otherwise empty
     */
    Optional<String> verify(String token);
}
