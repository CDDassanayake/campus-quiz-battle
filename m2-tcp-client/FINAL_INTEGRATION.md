# Final team integration verification

M2 is preserved and available develop changes are merged locally. Full system
integration is **blocked**. This report distinguishes production dependencies,
owner tests, local TLS verification and explicit mock demonstrations.

## Git preservation and upstream review

Initial branch: `feature/m2-tcp-client`, HEAD
`952f2078299d3e3cf97ea508be9ff76b23a12743`, seven commits ahead of its remote.
There were 118 untracked M2 files, one modified M2 POM and no staged files or
changes outside M2. Origin is
`https://github.com/CDDassanayake/campus-quiz-battle.git`.

The POM change supplies optional JavaFX 17.0.18 dependencies and runtime jar
preparation. It belongs to M2. Reviewed checkpoint:
`67e13f5c64e151456970bc2b657939ae0b7fb26e`,
`feat(m2): complete TCP client, JavaFX gameplay and final QA` (119 files).

Fetched develop: `a5bd2e6e42c5c478ded99a7a8e62d14ed9d01e2b`, merging M5.
It was merged into the feature as
`aa73f9ed64d0392ffe0d14b7001583cb28525404`. No conflicts occurred. SHA-256
comparison confirmed all 120 original M2 source/assets survived unchanged.
No reset, rebase, stash, force push or source deletion was used. The only files
added outside M2 came directly from the upstream M5 merge.

## Module availability and tests

| Module | Available implementation | Executed result |
|---|---|---|
| common | Frozen contracts and codec | Standard reactor: 42 tests, 41 pass, one failure |
| M1 | POM/package-info skeleton | No gateway implementation or tests; unavailable |
| M2 | TCP, state/service, console and JavaFX | 281 tests pass, no failures/errors/skips |
| M3 | POM/package-info skeleton | No discovery/feed implementation or tests; unavailable |
| M4 | Actual RoomManager/game engine | 78 tests pass |
| M5 | Five actual security classes | 29 tests: 24 pass, two failures, three errors |
| app | Dependencies and package-info skeleton | ServerMain/ClientMain absent; no implemented tests |

The standard unmodified `mvn -q clean test` fails at
`MessageCodecTest.testDecodeComplexEscaping`; dependent modules do not run in
that invocation. A separate package-focused run then executes M2/M4/M5 tests:

```powershell
mvn -q test '-Dtest=com.quizbattle.client.**.*Test,com.quizbattle.game.**.*Test,com.quizbattle.security.**.*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
```

That run fails at M5. It is not a successful full reactor run. No shared test or
implementation was changed or disabled. `mvn -q '-DskipTests' package` succeeds
as launch preparation only; it does not resolve either failing test suite.

M5 owner review is needed for these unsuccessful cases:

- `HmacTokenServiceTest.testVerifyTamperedToken`: failure. Its replacement-based
  tampering can leave a token unchanged; review the assertion and implementation.
- `HmacTokenServiceTest.testSameUserGetsDifferentTokensEachTime`: failure.
  Same-second expiry plus deterministic signing can produce identical tokens;
  review the intended uniqueness contract.
- `HmacTokenServiceTest.testTokenExpiry`: error. Test supplies zero TTL while
  the constructor requires positive TTL.
- `UserStoreTest.testVerifyNullUsername`: null-handling error.
- `UserStoreTest.testVerifyNullPassword`: null-handling error.

These are observed unsuccessful tests, not five independently demonstrated
security vulnerabilities. M2 prevalidates credentials and does not alter M5 to
make the suite pass. Raw test output may contain synthetic issued test tokens;
it is excluded from submission.

## Runtime verification

| Executed check | Result and scope |
|---|---|
| JavaFX smoke | 308 checks pass, including worker termination |
| Normal GUI | Window opens and regular close exits 0; no real provider installed |
| Explicit mock GUI | Window opens and regular close exits 0; complete mock gameplay also covered by smoke |
| Console demo | Login, room creation, both questions, winner Alice/230 and exit 0 |
| Actual built merged M5 | 18 TLS checks pass using `-UseWorkingTree -Revision HEAD -SkipBuild` |
| Isolated M5 source export | Existing default mode still passes 18 TLS checks |
| Production ServerMain | Attempt exits 1: ClassNotFoundException |
| Production ClientMain | Attempt exits 1: ClassNotFoundException |
| Java compatibility | Release 17 bytecode (major 61); executed on Microsoft JDK 21.0.8 |
| Actual JDK 17 runtime | Not available in configured/standard locations; pending |
| Three real client processes / UDP / physical LAN | Blocked by absent M1/M3/app; not performed |

TLS checks use real merged M5 server/client and verifier with temporary localhost
certificates and enabled trust/hostname validation. Their TCP handoff still uses
the explicit test gateway because production M1 is absent. They do not establish
real multiplayer gameplay, UDP discovery or full security assurance.

## Integration changes and publication boundary

`scripts/verify-real-security.ps1` adds opt-in `-UseWorkingTree`. It builds M2/M5
unless `-SkipBuild` is given, checks M5 source against the recorded revision and
uses the built module classes. Probe/provider output stays in ignored M2 target;
there is no permanent M2-to-M5 dependency or installed mock fallback. With
`-SkipBuild`, callers must first build the matching source.

Updated: README.md, INTEGRATION.md, REPORT.md, DEMO_GUIDE.md, SUBMISSION.md and
scripts/verify-real-security.ps1. Created: FINAL_INTEGRATION.md and
PULL_REQUEST.md. Common, M1/M3/M4/M5, app, shared configuration, protocol and root
POM were not edited locally. Existing tests and curated screenshots are preserved.

Step 8 requires successful integration/verification before feature publication;
that prerequisite is unmet. **Feature push is held. Develop merge/push is held.**
The feature branch is retained. A local PR description is prepared for review;
no remote PR was created and no protection was bypassed. The final Git hashes
are available with `git log -3 --oneline`; this report cannot include its own
containing commit hash. Generated logs, certificates, keys and build output stay
ignored. Final review uses scoped staged paths and `git diff --cached --check`.

## Launch and handoff

Run from the repository root:

```powershell
# Existing independent GUI modes:
& .\m2-tcp-client\scripts\launch-gui.ps1
& .\m2-tcp-client\scripts\launch-gui.ps1 -Mock
& .\m2-tcp-client\scripts\launch-gui.ps1 -Smoke

# Actual merged M5 TLS verification (not multiplayer):
& .\m2-tcp-client\scripts\verify-real-security.ps1 -UseWorkingTree -Revision HEAD

# Independent console with actual loopback TCP and explicit fake services:
java -cp 'm2-tcp-client/target/test-classes;m2-tcp-client/target/classes;common/target/classes' com.quizbattle.client.MockQuizDemo
```

Console: discover server 1, log in as alice using a nonempty demonstration
password, create Networks with two questions, wait for room membership, type
`start`, then `answer q1 2` and, after question 2 arrives, `answer q2 1`.
Choose 3 on the final screen to exit. All scores come from the scripted server.

There is currently **no working complete-system launch command**. The configured
entry points are absent; giving a server command as operational would be false.
M1 must supply the authenticated gateway, M3 discovery/feed, and the app owner
must supply ServerMain/ClientMain plus provider/lifecycle wiring. Shared codec
and M5 test issues require owner resolution. Once develop contains those changes,
fetch, inspect, merge and rerun the full reactor before repeating the real
three-client acceptance procedure in INTEGRATION.md. Record team JavaFX approval,
actual JDK 17 execution and any physical-device LAN testing separately.
