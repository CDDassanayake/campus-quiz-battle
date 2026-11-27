# Campus Quiz Battle: M2 integration handoff

Final submission material is in [REPORT.md](REPORT.md), [DEMO_GUIDE.md](DEMO_GUIDE.md)
and [SUBMISSION.md](SUBMISSION.md). Phase 8 retains all integration boundaries and
adds registration-notification race guards, consistent console validation/auth
waits, final QA documentation and nine curated actual screenshots.

## Evidence and ownership

Final integration checkpointed M2 as `67e13f5`, then merged fetched
`origin/develop` **a5bd2e6e42c5c478ded99a7a8e62d14ed9d01e2b** into
`feature/m2-tcp-client` as `aa73f9e`, without conflicts. All 120 original M2
source/assets matched their pre-merge SHA-256 hashes. The merge brings in the
upstream M5 implementation; no common contract, shared configuration, root POM
or other member's source was edited locally. See [FINAL_INTEGRATION.md](FINAL_INTEGRATION.md)
for current test results and blockers. The earlier Phase 7/8 review was read-only;
the subsequent final integration request explicitly authorized Git operations.

| Module | Fetched develop contents | Readiness / verification |
|---|---|---|
| M1 | POM and package-info only | No NioGatewayServer implementation; real game transport integration blocked |
| M2 | This local TCP/service/console/JavaFX implementation | Implemented and tested; real service injection supported |
| M3 | POM and package-info only | No discovery/feed implementations; actual UDP testing blocked |
| M4 | RoomManager, rooms/rounds, scoring, CSV/URL question sources and tests | 78 tests pass; M2-to-real-engine-through-NIO testing pending M1 |
| M5 | Five security classes plus tests, now merged | Actual merged TLS probe: 18 checks pass; owner JUnit suite: 24 pass, 2 failures, 3 errors |
| app | POM already depends on all five modules; package-info only | No ClientMain/ServerMain; runtime wiring pending integration owner |
| common | Frozen interfaces and codec | No differences between this branch and fetched develop; existing codec test failure remains |

The real M5 probe supports both an isolated unmodified source export and
`-UseWorkingTree -Revision HEAD`, which uses the actual built merged M5 classes.
Both modes passed 18 checks with fresh temporary certificates. Neither adds a
permanent Maven dependency, copies M5 into production sources or installs a
provider. The probe's test TCP gateway does not establish real NIO integration.

## Public M2 entry points

All types below already exist; no shared signature changes are required.

```java
new TcpGameClient(String host, int gatewayPort, String token);
new TcpGameClient(String host, int gatewayPort, String token, ClientTimeouts timeouts);
new GameClientService(ServerFinder finder, SecureLoginClient login,
        Supplier<? extends LiveFeedReceiver> receiverFactory);
// Also accepts ClientTimeouts, and optionally Duration roomReplyTimeout.
ClientApplication.launchInjected(service, "CAMPUS LAN", args);
ConsoleClient.system(service, config).run();
```

`TcpGameClient.connect()` and command methods block their caller: use the service
from application code. `GameClientService` exposes asynchronous discovery,
register/login/connect, listRooms/refreshRooms, createRoom/joinRoom/leaveRoom,
startGame, submitAnswer, ping and disconnect operations. Login/connect complete
only after actual WELCOME. Other command futures represent local transmission,
not server acceptance. `refreshRooms()` instead waits for a valid ROOMS response
and releases its shared wait after a bounded timeout so the next refresh can retry.

`submitAnswer(Question displayed, int zeroBasedChoice)` checks the displayed
instance on the serialized event executor, protecting delayed interactions even
when wire IDs are reused. The original String-ID overload remains for the console.
Scores/results come only from TCP RESULT/GAME_OVER; UDP is informational.

The service owns its TCP connection, per-session live-feed receiver, event/I/O
executors and request-deadline scheduler. Close it on application exit, then call
`awaitTermination(Duration)` off the FX thread and outside model observers.
JavaFX launchers do this automatically. The console's `run()` owns its cleanup.
The state reducer and service have no JavaFX dependencies.

## Verified constructors in other modules

These were read from actual source, not inferred from architecture examples:

```java
new TlsSecureLoginClient(String certificatePath); // implements SecureLoginClient
new UserStore();
new HmacTokenService(String secret);
new HmacTokenService(String secret, long ttlMinutes); // implements TokenVerifier
new TlsAuthServer(AppConfig config, UserStore users, HmacTokenService tokens);
// TlsAuthServer: start(), stop(), close(). Constructor binds immediately.
TlsContextFactory.createClientContext(String certificatePath);
TlsContextFactory.createServerContext(String keystorePath, char[] password);
new RoomManager(AppConfig config, LiveFeedPublisher publisher);
// RoomManager implements MessageHandler; executor() exposes its handler pool; close().
```

M3 concrete constructors and M1 lifecycle methods cannot be verified: those
classes do not exist yet. Do not treat the architecture's planned signatures as
implemented APIs. M1 must guarantee per-session ordered callbacks on M4's handler
executor, validate HELLO through the real TokenVerifier, and handle PING itself.

## Proposed app-owned wiring

The compilable [M5ManualProvider example](examples/M5ManualProvider.java) uses the
actual M5 constructor and common interfaces. The TLS probe compiles this example
against the exported actual M5 classes. It is outside M2 production sources and
is deliberately not registered with ServiceLoader.

For an initial **manual-address, TCP-only** integration build, the app owner can
move/adapt that example into app and use:

```java
AppConfig config = AppConfig.load("config/client.properties");
GameClientService service = new M5ManualProvider().create(config);
ClientApplication.launchInjected(service, "REAL TLS / MANUAL TCP / NO UDP", args);
```

The example returns an empty discovery list intentionally and supplies a null feed
factory. It performs no mock authentication or UDP; enter the real server address
manually. It still needs a real gateway before GUI login can complete. It has not
made normal mode integrated on this branch.

When M3 exists, app must replace the manual-only finder and null factory with
actual objects implementing ServerFinder and a factory producing a **fresh**
LiveFeedReceiver per connection:

```java
// finder and freshReceivers are supplied by M3 using its future verified constructors.
GameClientService service = new GameClientService(finder,
        new TlsSecureLoginClient(config.getString("security.server.cert.path")),
        freshReceivers, ClientTimeouts.fromConfig(config));
ClientApplication.launchInjected(service, "CAMPUS LAN", args);
```

Alternatively, app may implement GuiServiceProvider and provide
`META-INF/services/com.quizbattle.client.ui.GuiServiceProvider` containing that
implementation's fully qualified name. The provider must return a real service
or fail initialization. M2 never falls back to test services. With no provider,
normal mode reports SERVICES NOT CONFIGURED and disables network account actions.

App's existing POM already lists M1–M5 dependencies. Because M2's JavaFX dependencies
are **optional**, the integration owner must add javafx-controls/javafx-fxml
**17.0.18** to the GUI runtime, resolve the platform native jars, and launch with:

```text
java --module-path <resolved JavaFX jars> --add-modules javafx.controls,javafx.fxml
     -cp <app + common + M1/M2/M3/M4/M5 jars> com.quizbattle.app.ClientMain
```

Do not copy Maven placeholder paths literally. The existing M2 script demonstrates
dependency copying and module-path setup. A provider-based launch also needs the
app/provider and M3/M5 jars on the runtime classpath; the stock M2 script supplies
only M2/common and does not automatically discover sibling-module jars. The
integration owner must supply its app launcher; this phase did not edit app/POMs.
The console uses the same injected service and needs no JavaFX runtime.

JavaFX remains subject to team approval before integration/merge; local GUI work
was authorized. The final integration request subsequently authorized Git operations;
that does not establish team approval of the JavaFX technology choice.

## Configuration and certificates

Use the existing config files and AppConfig. Environment names map dots to
underscores, e.g. QB_CLIENT_SERVER_HOST / QB_CLIENT_GATEWAY_PORT / QB_CLIENT_TLS_PORT.
No server address, password or real token is hard-coded into M2 production code.

| Setting | Existing default / meaning |
|---|---|
| client.server.host | Empty; discovery or manual DNS name/IPv4/IPv6 (including scoped IPv6 supported by Java) |
| client.gateway.port | 5050, game TCP |
| client.tls.port | 5443, client authentication TLS |
| udp.discovery.port | 5051; M3 owner must consume it |
| udp.feed.port | 5052; M3 owner must consume it |
| udp.feed.mode / udp.multicast.group | broadcast / 230.0.0.1; actual M3 support pending |
| security.server.cert.path | secrets/server-cert.pem, trusted public certificate |
| gateway.port / tls.auth.port | Server-side game/TLS listen ports; must match selected client ServerInfo |
| security.keystore.path | Server-only PKCS12 path |
| security.token.ttl.minutes | Server setting; app must pass it to the actual HmacTokenService constructor |

Optional **M2** timeout properties, consumed by `ClientTimeouts.fromConfig(config)`:

```properties
client.connect.timeout.ms=3000
client.authentication.timeout.ms=4000
client.read.timeout.ms=0
```

These configure game TCP connect/WELCOME/idle reads, not the independent TLS
provider. Non-numeric or invalid values fail early. Read timeout zero deliberately
allows an idle persistent connection. The service's room reply deadline defaults
to eight seconds; the additive constructor accepts another positive Duration.
The GUI authentication wait is 60 seconds, allowing the current M5 client's
10-second connect plus 30-second response limits and the subsequent default TCP
    connect/WELCOME deadlines. M5's deadlines are fixed in its
source and its common interface exposes no cancellation/close method: M2 cannot
force a blocked third-party TLS call to stop. Such a worker may outlive the
three-second launcher cleanup wait; M5 owns improving that contract/implementation.

Manual input is syntactic validation only: no URL prefix, embedded port, paths,
spaces or control characters; ports are separate fields from 1–65535. Resolution
happens on a worker through InetAddress. A TLS certificate must cover the exact
hostname/IP chosen, including LAN IPs used instead of localhost.

For server setup, use the repository's existing generate-keystore script:

1. Set QB_KEYSTORE_PASSWORD to a strong local secret and QB_TOKEN_SECRET to a
   random secret (32+ characters), without echoing or committing either.
2. Set QB_EXTRA_SAN to `ip:<server LAN IP>` and run
   `scripts\generate-keystore.bat` on Windows (or the shell equivalent).
3. Keep keystore.p12/private key only on the server. Give clients only the public
   server-cert.pem and verify its fingerprint with the server owner.
4. Configure security.server.cert.path on each client and use the matching LAN
   hostname/IP. Regenerate/distribute the public certificate if that identity changes.

The probe does not run these shared scripts or write secrets/. It generates its
own short-lived localhost-only test keys under ignored target output, passes
passwords via process environment, and restores inherited environment values.
Never deploy those test keys. Clean target to remove them after retaining reports.

## What actual M5 testing established

Run from the repository root after fetching refs separately:

```powershell
& .\m2-tcp-client\scripts\verify-real-security.ps1
# Or pin the inspected source explicitly:
& .\m2-tcp-client\scripts\verify-real-security.ps1 -Revision a5bd2e6
# Verify the actual merged module instead of an isolated export:
& .\m2-tcp-client\scripts\verify-real-security.ps1 -UseWorkingTree -Revision HEAD
```

This is opt-in and not part of headless Surefire. It requires installed JDK
keytool/javac, opens a local ephemeral TLS port, and generates fresh certificates.
The script leaves provenance and verification.txt under target/real-m5/<run>/.
It does not fetch, merge, install software, change system trust or disable TLS
verification. Test-only reflection reads M5's bound ephemeral port because that
server exposes no getter. Production uses the configured TLS port normally.

The probe passed **18 checks**: actual M5 TLS registration and login, M2 password
clearing and safe duplicate/credential errors, actual token reception/verification,
tampered/malformed/correctly-signed expired tokens, reuse after disconnect, missing
certificate, untrusted certificate, trusted certificate with wrong host, and a
closed TLS port. A real token traverses M2's HELLO/WELCOME through an explicitly
**test TCP gateway**, verified by actual HmacTokenService. That gateway is not M1;
no NIO or game-engine integration is claimed. TLS timeout presentation is covered
with deterministic injected exceptions; an actual 30-second stalled TLS server
timeout was not exercised.

Findings for M5's owner (not changed in this phase):

- Six repeated invalid logins still return BAD_CREDENTIALS: LOCKED/rate limiting
  are not implemented. M2 supports the code if a future real provider emits it.
- Actual M5 wraps errors as `Server error: CODE - text`. M2 now recognizes only
  anchored known codes and discards server text/causes. Typed certificate/network
  exceptions produce safe actionable local messages.
- Authentication currently concatenates/splits raw `|` fields instead of using
  MessageCodec. The real delimiter-containing password probe yields BAD_MESSAGE;
  escaped credentials are not interoperable yet. M2 does not silently rewrite
  passwords or impose an invented password-character policy.
- Token expiry is base64url-encoded, and the textual HMAC encoding is encoded a
  second time, unlike PROTOCOL.md. M2 treats tokens as opaque and the real M5
  verifier accepts its own format. The owner/team must resolve the documented
  format discrepancy before interoperability with independent verifiers is claimed.
- Source review also found line limits checked after readLine and in characters,
  and server stop without a worker-termination wait. Those are M5 reliability
  responsibilities; the isolated finite requests finished, not a general DoS audit.

## Real three-client LAN acceptance (pending)

This procedure has **not** been executed: M1/M3/app implementations and three
physical clients are unavailable here. Do not label the mock journey LAN testing.

### Server owner

1. Integrate reviewed member branches under team approval. M2 is already
   checkpointed and fetched develop is merged into its feature branch. Resolve the shared codec test
   through its owner; M2 must not patch that codec/test locally.
2. Build available modules with `mvn clean test`. A known shared failure must
   remain visible in the report; `-DskipTests package` is only a launch preparation
   step, not evidence that acceptance tests passed.
3. Configure server.name, gateway.port=5050 and tls.auth.port=5443 (or agreed
   alternatives), discovery=5051/feed=5052 and broadcast/multicast on the campus LAN.
4. Prepare the certificate and environment secrets above. Construct actual
   UserStore/HmacTokenService/TlsAuthServer and M4 RoomManager, supplying the actual
   M3 publisher. Give the actual M1 gateway M4's handler/executor and M5 verifier.
5. App owner supplies ServerMain/ClientMain and resource ownership/close order.
   The documented root commands become usable only after those classes exist:
   `mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ServerMain`.
   GUI ClientMain additionally needs the JavaFX module-path launcher described above.
6. Confirm actual ports are listening and permit the agreed ports through the
   server/client firewalls. Do not expose keystore/private keys or HMAC secrets.

### Three client machines

1. Distribute only the public certificate, configure its path, and select/discover
   the correct server identity. Use three distinct valid usernames.
2. Launch the integrated real GUI on each machine. Verify its real-services badge
   and absence of test classpaths/providers. Register, log in, and verify WELCOME
   on three simultaneous persistent TCP connections.
3. Have one host create a room and two guests join it. Compare room IDs and known
   roster events (no complete initial roster is promised). Only the confirmed
   host gets Start; guests wait for the actual QUESTION.
4. Play at least three questions concurrently with different answers and one
   unanswered round. Check displayed wire IDs, index/total, exact options and
   zero-based outgoing choices. Repeat-click/hold keys; each local round sends once.
5. Compare each private RESULT with server logs (redact credentials/tokens). Own
   points/total must come from M4. All clients must see the same TCP winner and
   server-ordered final standings, including ties/missing-self cases.
6. Verify actual UDP countdown/leaderboard traffic on the configured interface,
   sender address and ports. Inject loss/reordering with M3's approved test tools;
   wrong-room/stale sequence packets must be ignored, and missing feed must not
   stop answers/results. Zero time must not generate a local RESULT.
7. During a round, let a guest leave, then disconnect the host. Verify M4's
   same-room JOINED host promotion preserves an active question/submitted answer.
   Ordinary recoverable errors must not terminate other authenticated clients.
8. Return to lobby via LEAVE, play a second game, log out/in again, and check that
   old feed callbacks do not affect the new session.
9. Stop the server during QUESTION, answer waiting, round result and final results.
   Clients must disable gameplay, clear session data and return to recovery.
   Reconnect manually; no restoration of an old game is assumed.
10. Repeat with a missing/untrusted/wrong-host certificate, invalid credentials,
    malformed/expired/tampered token, unavailable gateway, failed discovery and
    manual hostname/IPv4/IPv6 fallback where the LAN supports it.
11. Measure the actual M1 idle policy with an otherwise quiet lobby. Decide with
    M1 whether a lifecycle-managed PING interval is needed. No automatic heartbeat
    was added: there is no real M1 idle policy to verify yet; manual ping works.
12. Close all clients/server. Confirm sockets, UDP receivers, selectors, timers
    and executor threads terminate. Record module commit IDs, Java versions,
    machine addresses, protocol traces with secrets redacted, and screenshots.

M1/M4 acceptance must separately cover UTF-8, escaped fields, LF framing,
4096-byte limits, fragmented/coalesced TCP delivery, HELLO authentication,
PING/PONG, LIST_ROOMS/CREATE_ROOM/JOIN/LEAVE/START/ANSWER, all server messages,
per-session callback order and independent scoring. M2's automated transport
fixtures cover these client behaviors; real NIO behavior is still pending.

## Local verification and troubleshooting

Final local results: **281 ordinary M2 tests passed** (273 preserved + 8 final QA cases),
**308 graphical checks passed**, and **18 actual M5 probe checks passed**.
There are 134 current generated screenshot captures; nine representative images
are retained under docs/screenshots for the report and submission.
The requested standard reactor still fails the existing shared codec test
(42 common tests, one failure). Normal/mock launchers and the independent
two-round console demo opened, completed their checks and exited cleanly.

```powershell
mvn -q -pl m2-tcp-client -am clean test
# If the existing shared MessageCodecTest.testDecodeComplexEscaping still fails:
mvn -q -pl m2-tcp-client -am clean test '-Dtest=com.quizbattle.client.**.*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
& .\m2-tcp-client\scripts\launch-gui.ps1
& .\m2-tcp-client\scripts\launch-gui.ps1 -Mock
& .\m2-tcp-client\scripts\launch-gui.ps1 -Smoke
```

Console fixture (test services; actual loopback TCP, no real TLS/UDP):

```powershell
java -cp 'm2-tcp-client/target/test-classes;m2-tcp-client/target/classes;common/target/classes' com.quizbattle.client.MockQuizDemo
```

Actual console wiring is `ConsoleClient.system(realService, config).run()` in
app's eventual ClientMain; there is no ready real app entry point to launch today.
See README for the complete three-round mock GUI instructions.

| Symptom | Recovery / owner |
|---|---|
| SERVICES NOT CONFIGURED | Supply the real app/provider and its runtime dependencies; normal mode does not use mocks |
| No servers / discovery failure | Enter hostname/IP and separate game/TLS ports manually; real discovery awaits M3 |
| Connection refused/timeout | Check chosen game vs TLS port, server process and firewall; retry manually |
| Certificate missing | Receive the public server certificate and correct security.server.cert.path |
| TLS verification failure | Check trust/fingerprint, hostname/IP SAN and current certificate; never bypass validation |
| USER_EXISTS / BAD_CREDENTIALS | Choose another username/sign in, or correct credentials; raw provider text is never displayed |
| Invalid/expired token | Log in again; M1 must enforce the actual TokenVerifier result |
| Room full/missing/running | Refresh and select an advertised LOBBY room; server decides capacity |
| Room refresh timed out | Refresh again; the service releases the expired shared wait; late reply correlation remains ambiguous |
| Duplicate/late answer | Wait for RESULT or next QUESTION; do not claim local acceptance |
| Missing countdown / stale ranking | Last reported value/age stays visible; TCP gameplay continues |
| Connection lost | Return to login and reconnect manually; no speculative game restoration |
| Shutdown waits on TLS | Common has no cancellation API; M5's fixed deadlines/worker cleanup need owner review |

The graphical audit opens real JavaFX windows and dispatches JavaFX mouse/key
events/Button activation. It is distinct from OS-level manual input testing,
which remains pending. It captures every screen at four requested viewport
sizes and fullscreen, plus long texts, 201-player virtualized standings, dialogs,
loading and error states. Actual minimum outer-window 960x640 is also covered by
the original journey. Screenshots/reports are ignored artifacts under
target/gui-smoke; use -Smoke to regenerate them after clean.

JavaFX 17.0.18 and Java 17 release bytecode are used. Only JDK 21/24 were found
in the configured and standard locations; actual Java 17 runtime execution remains pending. No JDK was installed
and no system-wide Java setting was changed.

## Remaining protocol constraints

- JOINED has no initial roster snapshot; show only confirmed membership/events.
- LEAVE has no acknowledgement; clear room locally after sending and refresh.
- ERROR has no request/question correlation ID; delayed errors/replies cannot
  always be matched unambiguously after a retry.
- UDP countdown has no question ID. Room/state/range/monotonic checks cannot
  distinguish every delayed packet from the previous same-room round.
- Reused wire question IDs make delayed RESULT ambiguous; the local instance
  guard cannot add information absent from the wire.
- Final TCP data always overrides informational UDP. The server's announced
  winner and listing order are preserved; no client tie-break rule is invented.

Optional future work: heartbeat after agreeing M1's idle policy; actual Java 17
runtime/LAN tests; app-owned settings persistence if requested. No automatic
reconnect or Phase 8 work was introduced.
