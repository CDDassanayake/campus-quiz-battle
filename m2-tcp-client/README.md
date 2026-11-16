# M2 - TCP Client and Protocol

| | |
|---|---|
| **Owner** | Member 2 |
| **Concept** | TCP sockets (java.net.Socket), protocol design, addressing (InetAddress) |
| **Plan** | Part 2 of [plan.md](../plan.md) |
| **Provides** | TcpGameClient, console/Swing UI, MessageCodec (common.protocol), docs/PROTOCOL.md |
| **Consumes** | SecureLoginClient (M5), ServerFinder and LiveFeedReceiver (M3) |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Server-side code
- Direct UDP or TLS code (use the M3 / M5 interfaces)
- Changing protocol messages without updating docs/PROTOCOL.md and telling the team

## Packages
- `com.quizbattle.client.net`: TcpGameClient: connect with timeout, reader thread, sender, host resolution.
- `com.quizbattle.client.state`: Thread-safe client-side game state (room, question, scores).
- `com.quizbattle.client.ui`: User interface (console first, Swing optional): login, server list, lobby, question, results.
