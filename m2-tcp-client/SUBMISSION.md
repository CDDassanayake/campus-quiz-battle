# M2 submission readiness and proposed Git commands

## Current readiness

The available M2 implementation, regression tests, documentation and explicit
demonstration are ready for review. This is not a claim that the whole team's
real multiplayer system is integrated. See REPORT.md for evidence and
INTEGRATION.md for M1/M3/app dependencies, M5-owner issues, the shared codec failure,
JavaFX approval and pending physical LAN/Java 17 runtime/manual OS input tests.

Branch: `feature/m2-tcp-client`. HEAD remains
`952f2078299d3e3cf97ea508be9ff76b23a12743`. It is seven existing commits ahead of
origin/feature/m2-tcp-client. M2 implementation is largely untracked, with the
pre-existing M2 POM change. No commit, push, merge, rebase, reset or stash was
performed during final QA. Source/config changes outside M2 were absent.
At Phase 8 completion there were 118 untracked M2 files, including the preserved
earlier implementation and curated report assets, and no staged files.

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

The ordinary git diff does not show untracked implementation content: review
those files too. The historical seven ahead commits must be understood before
authorizing a push; pushing the branch would publish them along with the new M2
commit. Do not force-push or integrate develop without separate authorization.

## Proposed staging and publication: NOT EXECUTED

Only run these commands after reviewing the files and explicitly approving the
action. Do not replace the allowlist with git add . or git add -A.

```powershell
git add -- m2-tcp-client/.gitignore m2-tcp-client/pom.xml m2-tcp-client/README.md m2-tcp-client/INTEGRATION.md m2-tcp-client/REPORT.md m2-tcp-client/DEMO_GUIDE.md m2-tcp-client/SUBMISSION.md m2-tcp-client/src/main m2-tcp-client/src/test m2-tcp-client/scripts m2-tcp-client/examples m2-tcp-client/docs/screenshots
git diff --cached --name-only
git diff --cached --name-only -- . ':!m2-tcp-client'
git diff --cached --check
git diff --cached --stat
git diff --cached
```

The outside-M2 staged-name command must produce no output. If unrelated work was
already staged, stop and ask its owner how it should be handled; do not reset or
unstage someone else's changes. Verify that no target/, secrets, temporary TLS
material or IDE files are staged and that the nine curated images are intended.

After a separate approval to commit, and then a separate approval to push:

```powershell
git commit -m "feat(m2): complete TCP client, JavaFX gameplay and final QA"
git push -u origin feature/m2-tcp-client
```

These are recommendations, not authorization to execute them. No merge or PR
publication is included. JavaFX team approval and integration ownership decisions
must be recorded before the team treats this as an approved integration change.

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
