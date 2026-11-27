# M2 submission readiness and Git integration record

## Current readiness

The available M2 implementation, regression tests, documentation and explicit
demonstration are ready for review. This is not a claim that the whole team's
real multiplayer system is integrated. See REPORT.md for evidence and
INTEGRATION.md for M1/M3/app dependencies, M5-owner issues, the shared codec failure,
JavaFX approval and pending physical LAN/Java 17 runtime/manual OS input tests.

Branch: `feature/m2-tcp-client`. The final integration request explicitly
superseded the earlier prohibition on Git publication. The reviewed M2 checkpoint
is `67e13f5c64e151456970bc2b657939ae0b7fb26e` (119 files). Fetched develop
`a5bd2e6e42c5c478ded99a7a8e62d14ed9d01e2b` was merged without conflicts as
`aa73f9ed64d0392ffe0d14b7001583cb28525404`. All 120 original M2 source/assets
were preserved byte-for-byte across that merge. No reset, rebase, stash or force
push was used. Locally authored changes remain confined to M2.

See [FINAL_INTEGRATION.md](FINAL_INTEGRATION.md) for current verification,
publication status and blockers. Develop must not receive this feature while
significant failures and real integration prerequisites remain unresolved.

## Submission contents

Include the M2 .gitignore/POM; main Java/FXML/CSS sources; all test sources and opt-in
launchers; M2 scripts; the proposed app wiring example; README.md, INTEGRATION.md,
REPORT.md, DEMO_GUIDE.md and this file; and the nine curated actual screenshots
under docs/screenshots. Keep mocks in test sources. The example remains outside
production source and is not a registered provider or permanent M5 dependency.

Retain the historical PlaceholderController/placeholder.fxml files for existing
work preservation; no active Screen route uses them. They do not represent an
unfinished active quiz screen. The final GUI routes point to actual controllers.

Exclude target directories, compiled classes/jars, Maven reports and caches,
full screenshot matrices, temporary M5 source/class exports, probe keystores,
certificates, logs/crash dumps, .env files, secrets, IDE settings and OS metadata.
The existing root ignore rules cover target/, logs, secrets/, *.p12/*.pem/*.jks,
.env, .idea/.vscode/.settings, .project/.classpath, *.iml and common OS files.
Root rules were reviewed and not changed. M2's additional .gitignore excludes
loose *.class/*.jar output, .env variants and *.key/*.pfx material. Curated report
PNGs are intentional assets.

No real password, token, HMAC key or private key belongs in the submission.
Synthetic tokens in explicit test fixtures are not production credentials.
Public certificate paths/configuration examples are not certificates or secrets.

## Review commands: read-only

```powershell
git status --short --branch
git diff -- m2-tcp-client/pom.xml
git ls-files --others --exclude-standard -- m2-tcp-client
git diff --name-status HEAD -- . ':!m2-tcp-client'
git log --oneline origin/feature/m2-tcp-client..HEAD
git check-ignore -- m2-tcp-client/target m2-tcp-client/target/real-m5
```

The initial untracked implementation was reviewed and checkpointed before the
merge. Current changes must still be reviewed through both tracked diffs and
untracked-file listings. Stage explicit M2 paths only; inspect staged names and
`git diff --cached --check`. Generated target/log/TLS material must remain ignored.

## Authorized publication boundary

The user authorized checkpointing, fetching, merging develop into the feature,
verification and feature publication. Publication includes the seven historical
ahead commits reviewed before checkpointing. Step 8 makes feature publication
conditional on successful integration and verification; those conditions
currently fail. Both feature push and develop merge/push are held.
Do not merge or push develop, force-push, bypass branch protection, or change
another owner's source to conceal failures. The PR preparation in
[PULL_REQUEST.md](PULL_REQUEST.md) records this explicitly.

## Final verification evidence

Run the commands in DEMO_GUIDE.md. Keep the standard reactor failure and M2-only
passing suite separate. Report real TLS, mock TCP, JavaFX event-based checks and
pending real LAN tests separately. Keep generated logs in target for local review;
the report records the final result without committing the entire build output.

## Phase 8 change inventory

Created:

```text
.gitignore
REPORT.md
DEMO_GUIDE.md
SUBMISSION.md
src/test/java/com/quizbattle/client/Phase8QualityTest.java
docs/screenshots/01-login.png
docs/screenshots/02-registration.png
docs/screenshots/03-room-browser.png
docs/screenshots/04-waiting-room.png
docs/screenshots/05-quiz-and-leaderboard.png
docs/screenshots/06-answer-selection.png
docs/screenshots/07-round-feedback.png
docs/screenshots/08-winner-podium.png
docs/screenshots/09-connection-error.png
```

Updated existing files:

```text
README.md
INTEGRATION.md
src/main/java/com/quizbattle/client/service/GameClientService.java
src/main/java/com/quizbattle/client/ui/ConsoleClient.java
src/main/java/com/quizbattle/client/ui/controller/AuthController.java
src/test/java/com/quizbattle/client/GuiGameplaySmoke.java
src/test/java/com/quizbattle/client/GuiJourneySmoke.java
src/test/java/com/quizbattle/client/RealSecurityProbe.java
```

The last four import cleanups change no behavior. GameClientService adds stale
registration-notification guards. ConsoleClient shares manual address validation
with the GUI and allows the TLS-plus-TCP authentication budget while retaining
shorter ordinary command waits. The M2 POM modification predates Phase 8.
