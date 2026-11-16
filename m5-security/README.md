# M5 - Security (TLS Authentication)

| | |
|---|---|
| **Owner** | Member 5 |
| **Concept** | Secure sockets: JSSE (SSLServerSocket/SSLSocket), keystore/truststore, TLS, hashing/HMAC |
| **Plan** | Part 5 of [plan.md](../plan.md) |
| **Provides** | TlsAuthServer, TlsSecureLoginClient (SecureLoginClient), HmacTokenService (TokenVerifier), AppConfig |
| **Consumes** | nothing |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Game logic
- Touching the NIO gateway (M1 only calls your TokenVerifier)
- Committing keystores, passwords or secrets

## Packages
- `com.quizbattle.security.tls`: SSLContext factories, TlsAuthServer (SSLServerSocket) and TlsSecureLoginClient (SSLSocket).
- `com.quizbattle.security.auth`: User store and salted password hashing.
- `com.quizbattle.security.token`: HMAC-signed session tokens: issue and verify (TokenVerifier implementation).
- `com.quizbattle.security.crypto`: Small hashing/HMAC helpers.
