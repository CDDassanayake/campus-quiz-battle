# M1 - NIO Gateway

| | |
|---|---|
| **Owner** | Member 1 |
| **Concept** | Java NIO (Selector, ServerSocketChannel, SocketChannel, ByteBuffer) |
| **Plan** | Part 1 of [plan.md](../plan.md) |
| **Provides** | NioGatewayServer, ClientSession implementation |
| **Consumes** | MessageHandler (M4), TokenVerifier (M5), MessageCodec (common) |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Game rules, rooms or scoring (that is M4)
- UDP or TLS code
- UI code
- Blocking calls on the selector thread

## Packages
- `com.quizbattle.gateway`: M1: NIO gateway. Selector loop, per-connection sessions, newline framing, auth gate and dispatch to MessageHandler.
