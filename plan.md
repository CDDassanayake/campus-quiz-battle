# Campus Quiz Battle - Work Plan

Deadline: **Thursday 26 Nov 2026** (one ZIP: source + report PDF, one upload per group on Moodle). Plan to submit on **Wed 25 Nov**.
Evaluation: 10 minutes per group, **max 2 minutes per member** to demo and explain their own part.

Read `architecture.md` first. This plan has **5 parts, one per member**, with clear boundaries, plus a shared Part 0 and the integration steps at the end.

## Team

| Member | Name | GitHub | Part | Folder | Concept shown in the demo |
|---|---|---|---|---|---|
| M1 | _fill in_ | _fill in_ | Part 1 | `m1-nio-gateway/` | Java NIO |
| M2 | _fill in_ | _fill in_ | Part 2 | `m2-tcp-client/` | TCP sockets + protocol |
| M3 | _fill in_ | _fill in_ | Part 3 | `m3-udp-feed/` | UDP |
| M4 | _fill in_ | _fill in_ | Part 4 | `m4-game-core/` | Multithreading |
| M5 | _fill in_ | _fill in_ | Part 5 | `m5-security/` | Secure sockets (TLS) |

## Ground rules (everyone)

1. **Stay in your folder.** You only edit your own `mN-*` folder. A PR that touches another member's folder gets rejected.
2. **Branches:** `main` (final) ← `develop` (integration) ← `feature/mN-...` (yours). Open PRs into `develop`, never push to `main`.
3. **Contracts are frozen** after Part 0. Never change `common/` alone. If a contract is wrong, raise it in the group chat and make one change PR that all five approve.
4. **Never wait for a teammate.** Code against the interfaces in `common/contract` and test with mocks in your own `src/test` (each part lists the mocks you need).
5. **No secrets in Git.** Passwords, keystores, tokens and keys come from environment variables or the git-ignored `secrets/` folder.
6. **Everything you build must be demoable in 2 minutes.** Write your demo steps in your PR.
7. Commit small and often. Merge to `develop` at least when your core tasks are done (Milestone A).

Branch names:

```
feature/m1-nio-gateway    feature/m2-tcp-client    feature/m3-udp-feed
feature/m4-game-core      feature/m5-security      integration/<step>   (only for app/ wiring)
```

```bash
git checkout feature/m1-nio-gateway   # your branch
git pull origin develop               # get latest develop often
# ...work only in your folder...
git push origin feature/m1-nio-gateway   # then open a PR into develop
```

## Timeline

| Dates | Phase | Goal |
|---|---|---|
| Mon 16 – Tue 17 Nov | **Part 0** shared prerequisites | Contracts written, merged, frozen (tag `contracts-v1`) |
| Tue 17 – Thu 19 Nov | **Milestone A** | Every member finishes their **core** tasks and merges to `develop` |
| Fri 20 – Sun 22 Nov | **Integration I1 – I5** | Modules are wired together step by step; members finish `[stretch]` tasks and tests |
| Mon 23 – Tue 24 Nov | **I6 – I7** + report | Multi-machine test, hardening, report sections written |
| Wed 25 Nov | **I8 – I9** | Timed demo rehearsal, package ZIP, submit |
| Thu 26 Nov | Deadline | Buffer only |

---

## Part 0 - Shared prerequisites (everyone, about 2 hours each, Mon–Tue)

Goal: the `common` module exists and compiles, so Parts 1–5 can start in parallel. **Do not start your feature code before `contracts-v1` is tagged.**

| ID | Owner | Task | Done when |
|---|---|---|---|
| P0-1 | All | 30-minute meeting: read `architecture.md` sections 6 and 7 and `docs/PROTOCOL.md`; agree on any changes; fill in the Team table | Table filled, changes (if any) written in the docs |
| P0-2 | M2 | `common.protocol`: `MessageType`, `Message`, `MessageCodec` (encode/decode with escaping, max 4096 bytes) + unit tests | Round-trip tests pass for every message type in PROTOCOL.md |
| P0-3 | M1 | `common.contract.ClientSession` | Interface compiles |
| P0-4 | M3 | `common.model.ServerInfo`; `common.contract.LiveFeedPublisher`, `LiveFeedListener`, `LiveFeedReceiver`, `ServerAnnouncer`, `ServerFinder` | Interfaces compile |
| P0-5 | M4 | `common.contract.MessageHandler` | Interface compiles |
| P0-6 | M5 | `common.contract.TokenVerifier`, `SecureLoginClient`; `common.config.AppConfig` (loads `config/*.properties`, environment variables override) | Interfaces compile, `AppConfig` has unit test |
| P0-7 | All | Review each other's PRs into `develop`, then tag `contracts-v1` and rebase your feature branch on `develop` | Tag exists; `mvn -q -DskipTests install` succeeds |

Contract signatures are in `architecture.md` section 6. Copy them exactly.

---

## Part 1 - M1: NIO Gateway (`m1-nio-gateway/`)

**Concept to demonstrate:** Java NIO: `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer`, non-blocking I/O. (This part satisfies the assignment's NIO requirement.)

**Goal:** one thread serves every player's game connection.

**You provide:** `NioGatewayServer` and the `ClientSession` implementation.
**You consume:** `MessageHandler` (M4), `TokenVerifier` (M5), `MessageCodec` (common).

### Tasks (in priority order)

| ID | Task | Package |
|---|---|---|
| M1-T1 | Open a non-blocking `ServerSocketChannel` on `gateway.port`, register `OP_ACCEPT` | `gateway.server` |
| M1-T2 | Selector loop: accept new channels, read, write, cancel keys on error or close | `gateway.server` |
| M1-T3 | Per-connection state: read `ByteBuffer`, outgoing message queue, switch `OP_WRITE` interest on only when there is data to write | `gateway.session` |
| M1-T4 | Newline framing: partial reads, several messages in one read, drop the connection if a line exceeds 4096 bytes | `gateway.framing` |
| M1-T5 | Auth gate: the first message must be `HELLO|token` within 5 s. Verify with `TokenVerifier`; on success set the username and reply `WELCOME`, otherwise reply `ERROR|BAD_TOKEN` and close | `gateway.dispatch` |
| M1-T6 | Thread-safe `ClientSession.send()`: enqueue, then `selector.wakeup()` | `gateway.session` |
| M1-T7 | Dispatch decoded messages to `MessageHandler` **on the handler executor**, never on the selector thread; call `onConnect` / `onDisconnect` | `gateway.dispatch` |
| M1-T8 | Answer `PING` with `PONG` inside the gateway (the game core never sees it) | `gateway.dispatch` |
| M1-T9 | `[stretch]` Close connections idle for more than 60 s; cap the write queue (e.g. 256 messages) and disconnect slow readers | `gateway.session` |
| M1-T10 | `[stretch]` Graceful `stop()`: close channels, selector, no thread leaks | `gateway.server` |
| M1-T11 | Tests (see below) | `src/test` |

### Mocks you write in `src/test`
- `EchoMessageHandler`: a `MessageHandler` that echoes every message back.
- `AllowAllTokenVerifier`: accepts any token and returns the token text as the username.
- A load test using plain `java.net.Socket` clients (for example 100 clients, each sends 10 messages).

### Boundaries: do NOT
- Implement rooms, questions, scoring (M4) or parse message meaning beyond `HELLO` and `PING`.
- Write UDP, TLS or UI code.
- Block, sleep or do slow work on the selector thread.

### Definition of Done
- 100 simultaneous clients are served by **one** selector thread (log or `jstack` evidence).
- Messages split across several TCP segments and several messages in one segment are both handled.
- A connection without a valid `HELLO` is closed within 5 s.
- Tests pass: `mvn -q -pl m1-nio-gateway -am test`.

### Your 2-minute demo
1. Start the gateway with the echo handler (10 s). 2. Connect 3 clients and show messages echoed, then show the log line "1 selector thread, N clients" (30 s). 3. Send a half message, then the rest, to show framing (30 s). 4. Connect without HELLO and show it is dropped (20 s). 5. Explain `Selector` vs thread-per-client in 30 s.

### Your report section
Why NIO, selector loop design, framing and buffer handling, how backpressure is handled, screenshots, problems.

---

## Part 2 - M2: TCP Client and Protocol (`m2-tcp-client/`)

**Concept to demonstrate:** TCP client sockets (`java.net.Socket`, streams, timeouts), protocol design, host addressing (`InetAddress`).

**Goal:** the player-facing application and the owner of the wire protocol.

**You provide:** `TcpGameClient`, the UI, `MessageCodec` (done in P0-2), `docs/PROTOCOL.md`.
**You consume:** `SecureLoginClient` (M5), `ServerFinder` and `LiveFeedReceiver` (M3).

### Tasks (in priority order)

| ID | Task | Package |
|---|---|---|
| M2-T1 | `TcpGameClient.connect()`: resolve the host with `InetAddress.getByName`, connect with a timeout (`socket.connect(addr, timeoutMs)`), print the resolved IP | `client.net` |
| M2-T2 | Reader thread (`BufferedReader` on UTF-8 stream) that decodes lines with `MessageCodec` and notifies the state/UI; synchronized or queued sender | `client.net` |
| M2-T3 | Send `HELLO|token` on connect and wait for `WELCOME` (with `SO_TIMEOUT`) | `client.net` |
| M2-T4 | Command methods: `listRooms`, `createRoom`, `join`, `leave`, `start`, `answer`, `ping` | `client.net` |
| M2-T5 | Thread-safe client game state: current room, roster, current question, my score | `client.state` |
| M2-T6 | Console UI flow: choose server (from `ServerFinder` or type an IP/hostname), login/register (via `SecureLoginClient`), lobby (list/create/join/start), question screen with answer input, result screen, final standings | `client.ui` |
| M2-T7 | Implement `LiveFeedListener` and show the live countdown and leaderboard (use `LiveFeedReceiver` from M3) | `client.ui` |
| M2-T8 | Handle server disconnect, timeouts and `ERROR` messages with clear user messages | `client.net` |
| M2-T9 | `[stretch]` Swing/JavaFX UI instead of console | `client.ui` |
| M2-T10 | `[stretch]` Reconnect with the same token | `client.net` |
| M2-T11 | Tests (see below); keep `docs/PROTOCOL.md` up to date | `src/test` |

### Mocks you write in `src/test`
- `FakeGameServer`: a plain `ServerSocket` thread that plays scripted lines (WELCOME, QUESTION, RESULT).
- `FakeSecureLoginClient`, `FakeServerFinder`, `FakeLiveFeedReceiver`: return fixed values.

### Boundaries: do NOT
- Write any server-side code.
- Use `DatagramSocket` or `SSLSocket` directly (use M3's and M5's interfaces).
- Change a message in the protocol without updating `docs/PROTOCOL.md` and telling the team.

### Definition of Done
- Client connects, authenticates with a token, plays a full game against `FakeGameServer`.
- Wrong host, refused connection and server shutdown each show a readable error instead of a stack trace.
- Tests pass: `mvn -q -pl m2-tcp-client -am test`.

### Your 2-minute demo
1. Show the connect log with the resolved IP (15 s). 2. Join a room and play two questions (60 s). 3. Show the `ERROR|ALREADY_ANSWERED` handling and a clean message when the server is stopped (25 s). 4. Show the protocol table in `PROTOCOL.md` (20 s).

### Your report section
Protocol design and why text lines, client threading model, UI screenshots, timeout/error handling.

---

## Part 3 - M3: UDP Discovery and Live Feed (`m3-udp-feed/`)

**Concept to demonstrate:** UDP: `DatagramSocket`, `DatagramPacket`, broadcast, multicast, loss and reordering.

**Goal:** (a) clients find servers automatically, (b) everyone sees the same live countdown and leaderboard.

**You provide:** `UdpServerAnnouncer`, `UdpServerFinder`, `UdpLiveFeedPublisher`, `UdpLiveFeedReceiver`.
**You consume:** nothing. M4 pushes data into your publisher.

### Tasks (in priority order)

| ID | Task | Package |
|---|---|---|
| M3-T1 | Datagram codec per `PROTOCOL.md` section 4: encode/decode, max 512 bytes, ignore malformed packets | `udp.codec` |
| M3-T2 | `UdpServerAnnouncer`: daemon thread on `udp.discovery.port`; reply to `QB_DISCOVER|1` with `QB_SERVER|1|name|gatewayPort|tlsPort` | `udp.discovery` |
| M3-T3 | `UdpServerFinder.find(timeout)`: broadcast `QB_DISCOVER|1` (also to each network interface's broadcast address), collect replies until the timeout, remove duplicates, fill `ServerInfo.host` from the sender address | `udp.discovery` |
| M3-T4 | `UdpLiveFeedPublisher`: send `QB_CD` and `QB_LB` datagrams to the feed port with an increasing `seq`; max 4 per second per room | `udp.feed` |
| M3-T5 | `UdpLiveFeedReceiver`: bind with `SO_REUSEADDR`, parse, drop stale `seq` per room, call `LiveFeedListener`; clean shutdown on `close()` | `udp.feed` |
| M3-T6 | `[stretch]` Multicast mode (`MulticastSocket`, `joinGroup`) selected by `udp.feed.mode` for networks that block broadcast | `udp.feed` |
| M3-T7 | `[stretch]` Loss/reorder demo: a test helper that drops and reorders packets and shows the receiver still behaves | `src/test` |
| M3-T8 | Tests (see below) | `src/test` |

### Mocks you write in `src/test`
- A recording `LiveFeedListener`.
- Loopback tests: announcer + finder on `127.0.0.1`, publisher + receiver on loopback.

### Boundaries: do NOT
- Send any TCP game message or touch `ClientSession`.
- Read room or score state (M4 pushes it to you through `LiveFeedPublisher`).
- Write TLS code.

### Definition of Done
- Finder lists a running announcer within 2 s on loopback and on a real LAN.
- Receiver ignores malformed and out-of-order datagrams without crashing.
- No leftover threads or bound ports after `close()`.
- Tests pass: `mvn -q -pl m3-udp-feed -am test`.

### Your 2-minute demo
1. Start the announcer on one laptop, run the finder on another and show the server list (40 s). 2. Run the publisher and show the countdown on several receivers at once (40 s). 3. Explain why UDP is right here: drop a packet and show nothing breaks (40 s).

### Your report section
Why UDP for these two features, datagram format, broadcast vs multicast, sequence numbers, firewall/Wi-Fi problems met.

---

## Part 4 - M4: Game Core (`m4-game-core/`)

**Concept to demonstrate:** multithreading and concurrency: `ExecutorService`, `ScheduledExecutorService`, concurrent collections, locking, thread-safe state. Also `java.net.URI` / `URL` for optional question import.

**Goal:** all game rules and rooms, running correctly for several rooms and many players at the same time.

**You provide:** `RoomManager` (implements `MessageHandler`), the question bank, the shared handler `ExecutorService`.
**You consume:** `ClientSession.send()` (M1) and `LiveFeedPublisher` (M3).

### Tasks (in priority order)

| ID | Task | Package |
|---|---|---|
| M4-T1 | Question model and CSV loader for `questions/sample-questions.csv` (columns in the file header) | `game.question` |
| M4-T2 | `RoomManager implements MessageHandler`: `LIST_ROOMS`, `CREATE_ROOM`, `JOIN`, `LEAVE`, `START`, disconnect cleanup; send the replies defined in `PROTOCOL.md` | `game.room` |
| M4-T3 | Room state machine `LOBBY → RUNNING → FINISHED`, host rights, `ERROR` codes (`ROOM_FULL`, `NOT_HOST`, `GAME_RUNNING`, ...) | `game.room` |
| M4-T4 | Round engine on a `ScheduledExecutorService`: send `QUESTION`, wait `game.question.time.seconds`, close the round, send `RESULT` to every player | `game.engine` |
| M4-T5 | Scoring: first answer counts, correct = 100 points plus a speed bonus; handle `ALREADY_ANSWERED` and `TOO_LATE`; `GAME_OVER` with final standings | `game.engine` |
| M4-T6 | Push `publishCountdown` every second and `publishLeaderboard` after each answer batch to `LiveFeedPublisher` | `game.engine` |
| M4-T7 | Shared handler pool (size from `game.handler.pool.size`) in `game.concurrent`; expose `executor()` for the gateway; orderly shutdown | `game.concurrent` |
| M4-T8 | Document the locking strategy in a comment at the top of `Room` (what is guarded by what) | `game.room` |
| M4-T9 | `[stretch]` `UrlQuestionSource`: download a CSV from `game.questions.url` using `URI`/`URL`, fall back to the local file on failure | `game.question` |
| M4-T10 | `[stretch]` Player disconnect during a round does not stall or crash the game | `game.room` |
| M4-T11 | Tests (see below) | `src/test` |

### Mocks you write in `src/test`
- `RecordingClientSession`: stores every message sent to it.
- `RecordingLiveFeedPublisher`.
- A concurrency test: 50 fake sessions answer at the same time and the final scores are exactly the expected ones, run many times.

### Boundaries: do NOT
- Open any socket (`Socket`, `ServerSocket`, `DatagramSocket`, channels). You only use `ClientSession` and `LiveFeedPublisher`.
- Parse bytes or lines (the gateway delivers decoded `Message` objects).
- Run long or blocking work in a `MessageHandler` callback.

### Definition of Done
- Two rooms play full games at the same time without interfering.
- The 50-session concurrency test passes 100 times in a row.
- No data race on room or score state (every shared field is guarded or concurrent).
- Tests pass: `mvn -q -pl m4-game-core -am test`.

### Your 2-minute demo
1. Run the simulation with 2 rooms and show thread names in the log (40 s). 2. Run the 50-session concurrency test and show it passing (30 s). 3. Show the executor configuration and explain one race condition you prevented and how (50 s).

### Your report section
Concurrency design, thread-pool sizing, locking table, races found and fixed, optional URL import.

---

## Part 5 - M5: Security (`m5-security/`)

**Concept to demonstrate:** secure sockets: JSSE (`SSLContext`, `SSLServerSocket`, `SSLSocket`), keystore/truststore, TLS handshake, certificates, hashing and HMAC.

**Goal:** players log in over TLS and receive a signed token that the gateway can verify.

**You provide:** `TlsAuthServer`, `TlsSecureLoginClient` (`SecureLoginClient`), `HmacTokenService` (`TokenVerifier`), `AppConfig` (P0-6), the keystore scripts.
**You consume:** nothing.

### Tasks (in priority order)

| ID | Task | Package |
|---|---|---|
| M5-T1 | Keystore setup: test `scripts/generate-keystore.*` and write the "how to trust the server certificate" steps in your module README | `scripts/`, module README |
| M5-T2 | `SSLContext` factories: server side with `KeyManagerFactory` (keystore), client side with `TrustManagerFactory` (truststore); allow only TLS 1.3 and TLS 1.2 | `security.tls` |
| M5-T3 | `TlsAuthServer`: `SSLServerSocket` on `tls.auth.port`, worker pool, handle `REGISTER` and `LOGIN` per `PROTOCOL.md`; read timeout, max line length 4096 | `security.tls` |
| M5-T4 | User store: in-memory plus a file in `secrets/`, passwords stored only as **salted hashes** (PBKDF2 or SHA-256 with random salt) | `security.auth` |
| M5-T5 | `HmacTokenService implements TokenVerifier`: issue and verify `username.expiry.hmac` using HMAC-SHA256, key from `QB_TOKEN_SECRET`, expiry check, constant-time compare (`MessageDigest.isEqual`) | `security.token` |
| M5-T6 | `TlsSecureLoginClient implements SecureLoginClient` using `SSLSocket` with hostname verification enabled | `security.tls` |
| M5-T7 | `[stretch]` Login lockout after repeated failures; basic rate limit per IP | `security.auth` |
| M5-T8 | `[stretch]` Optional second truststore flag for a "bad" certificate to demonstrate a failed handshake | `security.tls` |
| M5-T9 | Tests (see below) and a short threat table for the report (eavesdropping, MITM, tampering, replay, spoofing, DoS) | `src/test` |

### Mocks you write in `src/test`
- Test keystore generated at test time (never committed).
- Tests: valid token, expired token, tampered token, wrong password, unknown certificate rejected, oversized line rejected.

### Boundaries: do NOT
- Implement game logic, rooms or scoring.
- Edit the NIO gateway. M1 only calls your `TokenVerifier`.
- Commit keystores, passwords or the token secret. No hard-coded secrets, ever.

### Definition of Done
- Login succeeds over TLS and returns a token that `verify()` accepts.
- A tampered or expired token is rejected; an untrusted certificate makes the client refuse to connect.
- Passwords never appear in plain text in files or logs.
- Tests pass: `mvn -q -pl m5-security -am test`.

### Your 2-minute demo
1. Generate the keystore and start `TlsAuthServer` (20 s). 2. Log in, show the token (20 s). 3. Show a capture or log where the same login without TLS would be readable and with TLS is not (30 s). 4. Change one character of the token and show rejection; connect with the wrong truststore and show the handshake fail (40 s). 5. Name the threats this stops (10 s).

### Your report section
TLS handshake diagram, keystore/truststore setup, token design, threat-to-defence table, the documented trade-off that game traffic is not TLS.

---

## Integration steps (Fri 20 – Wed 25 Nov)

Integration only edits `app/`. The **driver** of each step creates `integration/<step>` from `develop`, wires the modules, and everyone else keeps working in their own folder. Merge a step into `develop` only when its acceptance check passes. If a module fails a check, its **owner** fixes it in their own folder.

Prerequisite for all steps: Milestone A done (core tasks merged to `develop`).

| Step | Driver | Wire together | Acceptance check |
|---|---|---|---|
| **I1** (Fri 20) | M1 + M2 | `ServerMain`: `NioGatewayServer` with a stub handler and an allow-all verifier. `ClientMain`: `TcpGameClient` | Client connects, `HELLO` → `WELCOME`, `PING` → `PONG`, a client without `HELLO` is dropped after 5 s |
| **I2** (Fri 20 – Sat 21) | M4 + M1 | Replace the stub with `RoomManager`; use `RoomManager.executor()` as the gateway's handler executor | Two clients on one machine play a 3-question game: correct scores, `GAME_OVER` received |
| **I3** (Sat 21) | M3 + M2 | Start `UdpServerAnnouncer` in `ServerMain`; `ClientMain` uses `UdpServerFinder` instead of a typed IP | A client on a second laptop finds the server and connects |
| **I4** (Sat 21) | M3 + M4 | Give `RoomManager` the `UdpLiveFeedPublisher`; the client starts `UdpLiveFeedReceiver` with its `LiveFeedListener` | All clients see the same countdown (within 1 s) and a leaderboard that updates |
| **I5** (Sun 22) | M5 + M1 | Start `TlsAuthServer`; the gateway gets `HmacTokenService` as its `TokenVerifier`; the client logs in with `TlsSecureLoginClient` first. Remove the allow-all verifier | No token → connection closed; tampered token → `BAD_TOKEN`; valid login → game works end to end |
| **I6** (Mon 23) | All | Real test on at least 2 machines and 5 players over Wi-Fi (or a phone hotspot) | Full game completes with 5 players; discovery works or the manual IP fallback works; firewall rules documented in the README |
| **I7** (Tue 24) | All | Hardening: kill a client mid-game, slow client, 20 simulated clients, restart the server, wrong password, expired token | No server crash or hang; every failure shows a readable error |
| **I8** (Wed 25) | All | Demo rehearsal, timed with a stopwatch, in this order: M3 (discovery) → M5 (login) → M2 (play) → M4 (rooms/threads) → M1 (NIO internals). 2 minutes each, 10 minutes total | Whole demo fits in 10 minutes with every member explaining their own part |
| **I9** (Wed 25) | Any one | Package and submit (see below) | ZIP uploaded to Moodle, confirmation screenshot saved |

Integration issue rule: if something breaks, first reproduce it with the module's mocks. If it still fails with mocks, the owner fixes it. If it only fails when combined, the two owners fix it together in a short call.

## Submission checklist (I9)

- [ ] `mvn -q clean install` passes on a clean clone of `develop`
- [ ] `develop` merged into `main`
- [ ] Final report PDF saved in `docs/report/` (all five sections written by their authors)
- [ ] README tells an evaluator how to generate the keystore, set the two environment variables, build, and run server and client
- [ ] No secrets in the repo (`secrets/` has only `.gitkeep`)
- [ ] Create the ZIP from `main`: `git archive --format=zip -o QuizBattle_GroupXX.zip main` (this respects `.gitignore`)
- [ ] Unzip it somewhere else and run the demo once from that copy
- [ ] One designated member uploads the single ZIP to the Moodle link before the deadline

## Demo checklist (evaluation day)

- [ ] Laptops charged, same Wi-Fi (or phone hotspot as backup), Java 17+ and Maven installed
- [ ] `truststore.p12` copied to every client laptop; `QB_KEYSTORE_PASSWORD` and `QB_TOKEN_SECRET` set
- [ ] Firewall allows Java on ports 5050, 5051, 5052, 5443
- [ ] Server started before the evaluator arrives; one test game already played
- [ ] Each member knows their 2-minute script and can explain their code if asked
