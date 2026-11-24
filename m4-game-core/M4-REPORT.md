# M4 – Game Core: Implementation Report

Module: `m4-game-core` · Branch: `feature/m4-game-core` · Lead concept: **multithreading**
(`ExecutorService`, `ScheduledExecutorService`, concurrent collections, per-room locking)
plus **`java.net.URI`/`URL`** for question import.

---

## 1. Concept

The game server must serve many players in many rooms **at the same time** without
blocking the NIO selector thread. M4 owns:

- the **handler pool** (`ExecutorService`) used by M1 for every `MessageHandler`
  callback (`onConnect`, `onMessage`, `onDisconnect`);
- the **round scheduler** (`ScheduledExecutorService`) that fires countdown ticks and
  round deadlines;
- all shared mutable game state (rooms, rosters, scores, answers, timers), which is the
  only place in the system where concurrent mutation of application data happens.

M4 is also the module that demonstrates `java.net.URI`/`URL` directly: questions may be
downloaded from an HTTP(S) CSV before falling back to the bundled local file.

M4 talks to the rest of the system **only** through frozen `common` contracts:
`MessageHandler`, `ClientSession.send()`, `LiveFeedPublisher`, `AppConfig`,
`Message`/`MessageType`. It opens no sockets (the HTTP question download is a client
connection, explicitly required by the assignment).

---

## 2. Design

### 2.1 Components

| Class | Responsibility |
|---|---|
| `Question` | immutable question record; validates 4 non-blank options and `correctIndex ∈ [0,3]` |
| `CsvQuestionParser` | header-driven, quote-aware, BOM/CRLF-tolerant CSV parser; skips bad rows |
| `QuestionBank` | immutable, duplicate-id-free bank; `pick(n)` shuffles a copy |
| `QuestionSource` / `LocalQuestionSource` / `UrlQuestionSource` | local classpath/file loading; timed, size-capped HTTP(S) download with local fallback |
| `PlayerState` | per-player score, current-round points and answered flag (guarded by the room lock) |
| `Round` | immutable question/timing data + atomic closed flag + timer handles |
| `Room` | one room: players, host, state, questions, current round, scores, timers, lock |
| `RoomManager` | `MessageHandler` + lifecycle: command dispatch, round engine, scoring, live feed, pools |
| `GameSimulation` | standalone two-room demo with fake sessions and a printing feed |

### 2.2 State machine

```
LOBBY --START(host, ≥1 player)--> RUNNING --last round closed--> FINISHED
  ^                                  |                                |
  +-- JOIN/LEAVE (lobby only)        +-- QUESTION → answers → RESULT  +-- LEAVE → removed when empty
```

- New room starts in `LOBBY`.
- Only the host may `START`; only in `LOBBY`; at least one player.
- `JOIN` is rejected unless the room is `LOBBY` and not full (default cap 10).
- Any player may `LEAVE` in any state.
- If the host leaves, the **earliest remaining player** is promoted (insertion-ordered
  `LinkedHashMap`).
- A room is removed as soon as its last player leaves (including while `FINISHED`).

### 2.3 Threading model

| Threads | Count | Work |
|---|---|---|
| `m4-handler-N` | `game.handler.pool.size` (default 8) | all `MessageHandler` callbacks and all game tasks, including round closure |
| `m4-scheduler-N` | 2 | countdown ticks and deadline timers; timers only submit tasks to the handler pool |
| M1 selector | 1 | never runs game logic; enqueues callbacks on `RoomManager.executor()` |

`ScheduledFuture`s carry a **round token** (the round index). The close task is
`execute(() -> closeRound(roomId, roundIndex))`; `closeRound` re-checks the room, the state,
the current round and an atomic `markClosed()`. A stale timer after a room removal or a
newer round therefore does nothing.

### 2.4 Locking (see the long comment at the top of `Room.java`)

One `ReentrantLock` per room protects: player map, host, state, selected questions,
current round, completed-round counter, scores/answer flags, leaderboard timestamp and
the list of scheduled futures.

Rules:

1. Never hold two room locks — `RoomManager` resolves the room first, then locks only it.
2. `session.send()` **is** called while the lock is held because the contract says it only
   enqueues. This makes TCP ordering deterministic (`RESULT` before the next `QUESTION`).
3. `session.close()` is **never** called under a room lock (only duplicate-username
   rejection closes a session, and it holds no room lock).
4. The live-feed publisher is **never** called under a lock: a snapshot (immutable
   `LinkedHashMap`) is copied under the lock and published after unlocking.
5. Global maps are `ConcurrentHashMap`; room ids are `AtomicInteger`; lifecycle is
   `AtomicBoolean`; scheduled callbacks never mutate state directly.

---

## 3. Implementation highlights

### 3.1 Question bank and CSV

- Header-driven (`id,category,text,optionA..D,correctIndex`, case-insensitive, any order,
  extra columns allowed, rows must match the header width).
- UTF-8 BOM stripped; CRLF normalised; blank lines and `#` comments ignored.
- Quoted fields support commas, doubled quotes and embedded newlines.
- Row-level failures (bad number, index out of range, blank text/option, duplicate id)
  are logged at `FINE` and skipped; one bad row cannot kill the server.
- `pick(n)` shuffles a copy, never mutates the bank, never repeats a question.
- `RoomManager` clamps a requested round count to the bank size and rejects the command
  when no bank could be loaded.

### 3.2 Protocol compatibility (unchanged contracts)

M4 sends exactly the messages in `docs/PROTOCOL.md`:

- `ROOMS|id:name:players:state;…` (single field; empty string when no rooms)
- `JOINED|roomId|roomName|true|false`
- `PLAYER_JOINED|username`, `PLAYER_LEFT|username`
- `QUESTION|questionId|index|total|text|optA|optB|optC|optD|timeLimitSec`
- `RESULT|questionId|correctIndex|pointsAwarded|totalScore` (to **every** player)
- `GAME_OVER|winner|name:score;name:score`
- `ERROR|code|text`

### 3.3 Scoring

`correct → 100 + round(50 × secondsRemaining / timeLimit)`; wrong → 0.
The 15-second protocol example (9 s left) therefore gives exactly **130** points.
Only the first answer per player per round is accepted; the score is awarded once, under
the room lock. Late answers and answers racing with the close task are resolved by the
same lock plus the atomic round-closed flag.

### 3.4 Round engine and timers

1. `START` selects and shuffles questions, resets scores, moves to `RUNNING`.
2. `startNextRound` broadcasts `QUESTION` and creates a `Round` with the start/deadline.
3. A 1 Hz `scheduleAtFixedRate` ticker publishes `publishCountdown` (never takes a lock,
   never blocks the scheduler with game work).
4. A close task fires after `game.question.time.seconds` and submits `closeRound`.
5. `closeRound` validates the round token, marks closed, sends `RESULT` to everyone,
   updates the leaderboard, and either schedules the next round after
   `game.round.gap.millis` or sends `GAME_OVER` and moves to `FINISHED`.
6. If **all** connected players have answered, the round closes early (standard quiz UX;
   the timer remains the upper bound and is demonstrated when someone does not answer).

### 3.5 Live feed

- `publishCountdown(roomId, secondsLeft)` every second from the scheduler thread
  (`m4-scheduler-*`).
- `publishLeaderboard` after accepted answers, throttled to at most 4/second/room
  (`game.leaderboard.throttle.millis`, default 250 ms; values below 250 ms are clamped
  up so the four-per-second limit is always enforced) and unconditionally at every round
  close and at game end.
- Snapshots are immutable and published outside the room lock; a publisher exception is
  logged and never breaks the game.

### 3.6 URL question import

- `URI` → scheme validation (HTTP/HTTPS only) → `URL.openConnection()`.
- Connect timeout **3000 ms**, read timeout **5000 ms**, body capped at **1 MiB**.
- HTTP 2xx only; redirects followed; body parsed with the same `CsvQuestionParser`.
- Any failure (DNS, refused, 4xx/5xx, timeout, oversized body, malformed CSV) logs only
  the exception type (never the URL) and falls back to `LocalQuestionSource`.
- Blank URL bypasses the network entirely.

### 3.7 Shutdown

- `close()` is idempotent (`AtomicBoolean`).
- Marks every room removed, marks the current round closed, cancels all room futures.
- `scheduler.shutdownNow()`; `executor.shutdown()`; waits 5 s, then forces
  `shutdownNow()`; scheduler wait 1 s.
- Rejected submissions during shutdown are caught and logged at `FINE`.
- The publisher is owned by the application and is not closed by M4.

### 3.8 Disconnects

- Removes the username→session mapping with `remove(user, session)` (never evicts a newer
  session).
- Removes the player from the room, broadcasts `PLAYER_LEFT`, promotes a new host if
  needed, publishes the leaderboard.
- Round timers keep running; if the remaining players have all answered the round closes
  early; the remaining players finish the game normally.
- Last player out removes the room and cancels its timers; stale tasks then fail the
  `rooms.get`/`removed`/round-token checks.
- A disconnected session receives nothing after `onDisconnect` returns.

---

## 4. Testing and problems encountered

### 4.1 Automated tests

`mvn -q -pl m4-game-core test`:

| Test class | Tests |
|---|---:|
| `CsvQuestionParserTest` | 13 |
| `QuestionBankTest` | 9 |
| `UrlQuestionSourceTest` | 9 |
| `RoomManagerLifecycleTest` | 12 |
| `RoomManagerGameTest` | 10 |
| `RoomManagerErrorsTest` | 16 |
| `DisconnectTest` | 6 |
| `TwoRoomIsolationTest` | 2 |
| `ConcurrencyTest` | 1 (100 iterations) |
| **Total** | **78, 0 failures, 0 errors** |

Highlights: exact 125/105/0 point assertions with a frozen `LongSupplier` clock;
automatic closure at 1 s; early closure before a 5 s timer; leaderboard ordering with
ties; host promotion; capacity; duplicate-username rejection; disconnect during a round;
two-room isolation; graceful pool termination; URL success, 500, malformed CSV, timeout,
oversized body, refused connection, blank URL and unsupported scheme.

### 4.2 Concurrency stress result

`ConcurrencyTest`: **50 sessions, 2 rooms (25 each), 100 iterations**, all first answers
released by a `CountDownLatch`, each player immediately sending a duplicate answer.

Result: `100 iterations x 50 sessions in ~500–900 ms` per run (three consecutive full-suite
runs passed), with every iteration asserting:

- exactly one `QUESTION`, one `RESULT` and one `GAME_OVER` per player;
- exactly 150 points awarded once (a duplicate would show 300);
- exactly one rejection of the duplicate (`ALREADY_ANSWERED` or `TOO_LATE`);
- each room's standings and every leaderboard contain exactly its own 25 players;
- the winner is the lexicographically first name among equals.

### 4.3 Problems encountered and resolved

1. **Stale timers after room removal** — solved with a room-removed flag, a round token
   and cancelling all futures; the ticker also reads an atomic round-closed flag.
2. **Answer-versus-deadline race** — both paths take the room lock; the first to close
   the round wins, and a late answer sees `isClosed()` or the clock deadline.
3. **Duplicate scoring** — the per-player answered flag, first-answer check and atomic
   round closure are all under one lock; the stress test proves no double award.
4. **Message ordering** — `RESULT` must be enqueued before the next `QUESTION`. Game
   messages are sent while holding the room lock (safe, because `send()` only enqueues)
   and the next round is scheduled only after `RESULT` has been enqueued.
5. **Unbounded HTTP body** — reading is capped at 1 MiB; the test covers an oversized
   response.
6. **CSV edge cases** — BOM, CRLF, quoted commas/quotes/newlines and malformed rows are
   covered by unit tests.
7. **Rejected duplicate connection evicted the real player** (found in the post-implementation
   audit) — a rejected second session for the same username still produces `onDisconnect`;
   that used to remove the original player from their room. `onDisconnect` now only releases
   the username when `remove(username, session)` succeeds, and a regression test covers it.
8. **Leaderboard throttling** had no test; a regression test now proves the 250 ms floor
   suppresses intermediate publishes while the round close always publishes.

### 4.4 Unrelated failure in `common` (reported, not modified)

`mvn -pl m4-game-core -am test` stops in `common`:

```
MessageCodecTest.testDecodeComplexEscaping:113
expected: <[Test\|<newline>, A|B\C]> but was: <[Test\|<backslash>n, A|B\C]>
```

The codec is correct; the test's expected string uses a real newline where the input
encodes a literal backslash + `n`. One-character fix for M2 (owner of `common`), at
`common/src/test/java/com/quizbattle/common/MessageCodecTest.java:113`:
change `"Test\\|\n"` to `"Test\\|\\n"`. `common` was left untouched.

`-Dmaven.test.failure.ignore=true` runs the full reactor and shows
**M4: 78/78 passing** alongside the unrelated common failure.

---

## 5. Concurrency decisions

| Decision | Why |
|---|---|
| One lock per room, not a global lock | rooms are independent; answers in different rooms should not serialise |
| Send under the room lock | `ClientSession.send` is contractual non-blocking; guarantees `RESULT` → `QUESTION` order |
| Publish feed outside the lock | UDP is another module's I/O; keeps critical sections short and avoids lock inversion |
| Scheduler only submits to the handler pool | game state is mutated on one pool, with validated round tokens |
| Ticker lock-free | round timing is immutable and `isClosed()` is atomic; no room lock on the timer path |
| Frozen `LongSupplier` clock in tests | exact, repeatable speed-bonus assertions without sleeps |
| Early close when all answered | better UX and fast tests; the timer remains the correctness upper bound |
| `ConcurrentHashMap` + `AtomicInteger`/`AtomicBoolean` | global maps and lifecycle need no room lock |

---

## 6. Lessons learned

- A single lock per aggregate (room) is much easier to reason about than fine-grained
  locks, and the critical sections are tiny because `send()` does not block.
- Timer callbacks are the hardest part: always validate an identity token and never let a
  timer own mutable state.
- A controllable clock turns flaky timing tests into exact arithmetic.
- The protocol needs an explicit host-change message (see below); reusing `JOINED` works
  but must be agreed with M2.

---

## 7. Protocol ambiguities requiring M2 approval

1. **Host promotion notification** — there is no `HOST_CHANGED` message, so a promoted
   player receives a second `JOINED|roomId|roomName|true`. M2's client should treat a
   second `JOINED` as a host-role update.
2. **Early round close** when every player has answered (before the time limit).
3. **Question count larger than the bank** is clamped instead of rejected.
4. **Room capacity** default 10 (new optional key `game.room.max.players`).
5. **`JOIN` a FINISHED room** returns `ERROR|GAME_RUNNING` (closest existing code);
   `ANSWER` in the lobby returns `ERROR|BAD_MESSAGE`; `ANSWER` after the game returns
   `ERROR|TOO_LATE`.
6. **Empty room list** is encoded as `ROOMS|` (one empty field).
7. New optional configuration keys: `game.round.gap.millis` (default 3000),
   `game.leaderboard.throttle.millis` (default 250), `game.room.max.players` (default 10).

None of these change `common/`; they only clarify `docs/PROTOCOL.md`.

---

## 8. Build, test and demo commands

```bash
# Full build (passes)
mvn -q -DskipTests install

# M4 tests (78 passing; common already installed)
mvn -q -pl m4-game-core test

# 50-session stress test, custom iteration count
mvn -q -pl m4-game-core test -Dtest=ConcurrencyTest -Dgame.stress.iterations=100

# Standalone two-room demo (in-memory; no M1/M2/M3/M5 needed)
mvn -q -pl m4-game-core exec:java
```

> Note: `mvn -q -pl m4-game-core -am exec:java` executes `exec:java` on the aggregator
> project as well and fails there. The working command is the one above (or run the class
> with `java -cp m4-game-core/target/classes:common/target/classes`).

### Simulation output (abridged)

```
Handler pool threads: [m4-handler-1, m4-handler-2, m4-handler-3, m4-handler-4]
Room r1 (Algorithms) has 4 players
Room r2 (Networks) has 3 players
[m4-scheduler-1] feed room=r2 countdown=2s
[m4-handler-1] erin <- RESULT | 9 | 1 | 145 | 291
[m4-handler-2] bob  <- GAME_OVER | bob | bob:143;carol:142;alice:0;dave:0
--- final leaderboards (published to the live feed) ---
room r2 -> {erin=291, frank=0, grace=0}
room r1 -> {bob=143, carol=142, alice=0, dave=0}
Rooms still tracked by RoomManager: 2
```

---

## 9. Integration notes

**I2 (M1 + M4):**

```java
LiveFeedPublisher feed = /* NoOp until I4 */;
RoomManager game = new RoomManager(AppConfig.load("config/server.properties"), feed);
NioGatewayServer gateway = new NioGatewayServer(5050, game, verifier, game.executor());
// shutdown hook: gateway.stop(); game.close();
```

**I4 (M3 + M4):** pass `UdpLiveFeedPublisher` instead of the no-op. M4 needs no other
change; it already publishes countdowns and leaderboards in the required format.

**M2:** handle a second `JOINED` as host promotion and expect early `RESULT`/`GAME_OVER`
when everyone answers quickly.

**M1:** use `RoomManager.executor()` for every callback, and preserve the documented
per-session ordering guarantee.

---

## 10. Suggested commit message

```
feat(m4): implement multithreaded game core (rooms, rounds, scoring)

- immutable question model, header-driven CSV parser with quoted fields/BOM,
  question bank with randomized duplicate-free selection
- RoomManager (MessageHandler) with LOBBY/RUNNING/FINISHED rooms, host rights,
  capacity, duplicate-username rejection, disconnect cleanup and host promotion
- round engine on a 2-thread scheduler with validated round tokens and a shared
  8-thread handler pool (m4-handler-N), graceful idempotent shutdown
- scoring 100 + round(50 * remaining / limit), exactly-once first answers,
  ALREADY_ANSWERED/TOO_LATE handling, ordered GAME_OVER standings
- per-room ReentrantLock (documented strategy), feed publishing outside locks,
  1 Hz countdown and throttled leaderboards
- Java URI/URL question import with 3 s/5 s timeouts, 1 MiB cap and local fallback
- 78 JUnit tests including a 50-session/2-room/100-iteration stress test
- GameSimulation demo and Maven exec configuration
```
