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
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Game rules, rooms or scoring (that is M4)
- UDP or TLS code
- UI code
- Blocking calls on the selector thread

## Packages
- `com.quizbattle.gateway.server`: Selector loop: accept, read, write, key handling, start/stop.
- `com.quizbattle.gateway.session`: ClientSession implementation: per-connection buffers, write queue, OP_WRITE handling.
- `com.quizbattle.gateway.framing`: Newline framing over a byte stream: partial reads, multiple messages per read, max line length.
- `com.quizbattle.gateway.dispatch`: Auth gate (HELLO + TokenVerifier) and hand-off of decoded messages to MessageHandler on the handler executor.
