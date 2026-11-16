# M4 - Game Core (Multithreading)

| | |
|---|---|
| **Owner** | Member 4 |
| **Concept** | Multithreading and concurrency (ExecutorService, ScheduledExecutorService, concurrent collections); URL/URI for optional question import |
| **Plan** | Part 4 of [plan.md](../plan.md) |
| **Provides** | RoomManager (MessageHandler), question bank, shared handler executor |
| **Consumes** | ClientSession (M1), LiveFeedPublisher (M3) |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Sockets of any kind
- Parsing raw bytes
- UI code
- Calling anything that blocks the handler executor for long

## Packages
- `com.quizbattle.game.room`: Room and RoomManager: lobby, join/leave, state machine. RoomManager implements MessageHandler.
- `com.quizbattle.game.engine`: Round loop, timers, scoring.
- `com.quizbattle.game.question`: Question model, CSV loader, optional URL import (java.net.URI / URL).
- `com.quizbattle.game.concurrent`: Executors and thread-safety helpers shared by the game core.
