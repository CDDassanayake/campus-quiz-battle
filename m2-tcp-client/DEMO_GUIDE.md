# M2 demonstration guide

## Preparation and honest scope

Use JDK 17+ with javac/keytool, Maven 3.9+, PowerShell and a graphical desktop.
Local verification used Java 21 with release 17 compilation and JavaFX 17.0.18;
actual Java 17 runtime testing is still pending. Run commands from the repository
root. Maven downloads dependencies on the first build; runtime visuals use local
JavaFX shapes/assets. Use throwaway credentials only.

| Demonstration | What actually runs |
|---|---|
| GUI -Mock | Real loopback game TCP; test authentication/discovery/feed callbacks; three scripted questions |
| Console MockQuizDemo | Real loopback game TCP; test services; independent two-question fixture |
| -Smoke | Actual rendered JavaFX windows, event-based input and real mock TCP; automated screenshots |
| verify-real-security | Actual unmodified M5 TLS server/client and verifier with temporary certificates; test TCP gateway |
| Normal GUI | Real-provider entry point; currently SERVICES NOT CONFIGURED without app wiring |
| Physical multiplayer LAN | Pending M1/M3/app implementations and three devices; procedure in INTEGRATION.md |

Do not call the mock journey real TLS/UDP/LAN integration. Do not show the M5
test gateway as M1's NIO implementation. Do not announce gameplay acceptance or
scores from a successful socket write.

## Build and verify before presenting

```powershell
mvn -q -pl m2-tcp-client -am clean test
```

The standard command still fails common's existing
MessageCodecTest.testDecodeComplexEscaping. Show that separately; do not modify,
disable or conceal the test. Then run the complete M2 selection:

```powershell
mvn -q -pl m2-tcp-client -am clean test '-Dtest=com.quizbattle.client.**.*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
& .\m2-tcp-client\scripts\launch-gui.ps1 -Smoke
& .\m2-tcp-client\scripts\verify-real-security.ps1
```

Expected final evidence is 281 passing M2 tests (273 preserved + 8 final QA
regressions), 308 graphical checks and 18 actual M5 checks. Smoke captures are in
target/gui-smoke; actual TLS results/provenance are in target/real-m5/<run>/.
Clean removes these build artifacts. Nine selected report screenshots are retained
under docs/screenshots and listed below.

## Suggested eight-minute presentation

| Time | Action and explanation |
|---|---|
| 0:00–0:45 | Explain M2 ownership, architecture and the distinction between real TCP, mock services and isolated real TLS |
| 0:45–1:45 | Discovery/manual selection, registration and login; point out actual HELLO/WELCOME before Connected |
| 1:45–2:45 | Browse rooms, create a room, show host/guest waiting behavior and the lack of a separate READY message |
| 2:45–4:30 | Play three GUI questions; demonstrate mouse/keys, submitted waiting state, results and informational countdown/rankings |
| 4:30–5:15 | Show final winner/standings and Return to Lobby; briefly demonstrate the guest join path |
| 5:15–6:00 | Show leave confirmation, recovery/error captures, resizing and reduced motion |
| 6:00–7:00 | Present real M5 probe evidence: registration/login, certificate and hostname rejection, token verification; explain remaining M5 gaps |
| 7:00–8:00 | Present regression totals, known common failure, console evidence and the pending real three-client LAN acceptance procedure |

The timers belong to the scripted test server. Allow its three-second result gaps
and the unanswered round's 15-second deadline; never click a fabricated Next button.

## Complete graphical mock journey

1. Launch:

   ```powershell
   & .\m2-tcp-client\scripts\launch-gui.ps1 -Mock
   ```

2. Show the MOCK MODE badge and initial Disconnected state. Choose Find a Battle.
   The discovery service is a test callback fixture. Select the mock server card,
   or copy its hostname and dynamic game/TLS ports into the manual form. Do not
   assume ports 5050/5443 identify the fixture: its ports are allocated by the OS.
3. Choose Create an account. Enter `alice`, a nonempty throwaway password and
   matching confirmation. Registration returns to login; this fake service does
   not persist accounts or perform TLS/password verification. Sign in with `alice`.
   Explain that the game connection now performs actual TCP HELLO/WELCOME.
4. Browse/search/filter rooms. RUNNING/FINISHED entries cannot be joined. Create a
   room named `Campus Champions` and request three questions. For an optional
   recoverable error, first try `Taken`, then correct the room name.
5. Wait for actual JOINED. Point out the known-player roster and its initial-roster
   limitation. Only the confirmed host sees Start Battle. This is the ready state:
   WAITING_ROOM, with host-controlled START. There is no per-player READY command.
6. Start. On question 1 choose **B / TCP** with the mouse or key **2**. Show immediate
   Sending, then Answer sent / waiting for the server. Repeated clicks cannot
   change the answer. Do not call write completion confirmed acceptance.
7. Show the matching RESULT: correct selection and 100 fixture points. The next
   QUESTION comes from the test server after three seconds.
8. On question 2 choose **A**, key **1**. This incorrect selection deliberately
   receives 7 fixture points. Explain that the client displays the server's values
   rather than assuming a wrong answer always scores zero.
9. Leave question 3 unanswered. Observe reported time and the UDP-interface ranking
   callbacks. No real UDP packets are sent in mock mode. Missing ticks retain the
   last value; even a displayed zero cannot generate RESULT or advance the game.
10. After the test server's deadline, show Unanswered, zero awarded fixture points,
    then GAME_OVER. Winner `bob`, personal total 107 and four-player standings are
    scripted server data. Explain final TCP authority and server listing order.
11. Return to Lobby. The client sends LEAVE, clears local room state and requests
    ROOMS without claiming a leave acknowledgement.
12. Join Networks/Campus Rivals for the guest path. The fixture's external host
    starts after three seconds; guests have no Start button. Use Leave room to
    show confirmation. Cancel to preserve the question, or confirm to return.
13. Logout/Disconnect, then close the window. State, TCP/feed resources, observers
    and animations should be released.

During gameplay use keys 1–4 or numpad equivalents; Tab/Space/Enter provide normal
control activation. Number shortcuts are suppressed while a dialog is open and
held keys remain latched across transitions. F11 toggles fullscreen and Esc exits.
Settings exposes reduced motion. Long content remains reachable by scrolling.

## Error and edge-case demonstration

The automated -Smoke journey provides deterministic captures of failed login,
duplicate username, unavailable/empty discovery, room rejection, TOO_LATE,
ALREADY_ANSWERED, missing/stale feed and server disconnection. It also checks rapid
round transitions, held keys across dialogs, minimum layouts, long text, empty
finals, tied winners and 201-player standings. Show the actual captures rather
than improvising undocumented production controls for those fixture cases.

Normal startup is independently available:

```powershell
& .\m2-tcp-client\scripts\launch-gui.ps1
```

Without an installed real provider it must say SERVICES NOT CONFIGURED and must
not authenticate through mocks. That limitation is part of the integration handoff.

## Actual TLS security demonstration

```powershell
& .\m2-tcp-client\scripts\verify-real-security.ps1 -Revision a5bd2e6
```

The script compiles the actual fetched M5 source and proposed app-owned wiring
example without changing branches or adding an M2-to-M5 Maven dependency. It uses
fresh localhost certificates, environment-held temporary secrets and an ephemeral
TLS port. Trust and hostname verification stay enabled.

Show its 18 passing checks for real registration/login, safe errors, token receipt
and verifier rejection of malformed/tampered/expired tokens, missing/untrusted
certificates, hostname mismatch and connection refusal. Token handoff crosses a
test TCP gateway verified by actual HmacTokenService. This is not NIO gameplay.
Never display private keys, keystore passwords, HMAC secrets or actual tokens.

Explain remaining M5-owner issues: credential escaping, token wire-format
discrepancies, missing lockout/rate limiting and provider cancellation/cleanup.
No insecure workaround was introduced by M2.

## Independent console fallback

```powershell
java -cp 'm2-tcp-client/target/test-classes;m2-tcp-client/target/classes;common/target/classes' com.quizbattle.client.MockQuizDemo
```

On Linux/macOS replace classpath semicolons with colons. Discover/select server 1,
choose Login, enter `alice` and a throwaway password. Create Networks with two
questions, wait for JOINED and enter `start`. Answer `answer q1 2`, then
`answer q2 1`, waiting for each question. Show total 230, winner alice, then choose
3 to exit. This is the independent two-question fixture, not the three-question
GUI game. Password masking depends on the terminal and its limitation is labelled.

## Real multiplayer presentation: pending

Follow the complete server/client procedure in [INTEGRATION.md](INTEGRATION.md)
only after actual M1 gateway, M3 discovery/feed and app launchers exist. The app
owner must inject real services and provide JavaFX runtime jars. Generate the real
LAN certificate with existing scripts, distribute only its public certificate,
configure ports/firewalls, and run three clients with distinct users in one room.
Verify synchronized questions, independent answers, M4 scores, actual UDP traffic,
host promotion, leaving, server shutdown and clean exit. Record exact commits,
Java versions and sanitized traces. Do not claim that procedure has passed here.

## Curated screenshot index

These are actual JavaFX screenshots from the final explicit mock smoke run.
One quiz capture covers both arena and leaderboard to avoid redundant images.

| File under docs/screenshots | Report/demo subject |
|---|---|
| [01-login.png](docs/screenshots/01-login.png) | Login |
| [02-registration.png](docs/screenshots/02-registration.png) | Registration |
| [03-room-browser.png](docs/screenshots/03-room-browser.png) | Room browser |
| [04-waiting-room.png](docs/screenshots/04-waiting-room.png) | Host waiting / ready state |
| [05-quiz-and-leaderboard.png](docs/screenshots/05-quiz-and-leaderboard.png) | Active arena and live rankings |
| [06-answer-selection.png](docs/screenshots/06-answer-selection.png) | Selected answer and immediate sending feedback |
| [07-round-feedback.png](docs/screenshots/07-round-feedback.png) | Authoritative correct-answer result |
| [08-winner-podium.png](docs/screenshots/08-winner-podium.png) | Final winner and podium |
| [09-connection-error.png](docs/screenshots/09-connection-error.png) | Connection-loss recovery |

The larger resolution matrix remains in ignored target/gui-smoke/responsive.
Check it locally; do not stage the entire generated image collection or temporary
TLS artifacts. See [SUBMISSION.md](SUBMISSION.md) for the scoped submission procedure.
