# Prepared PR: M2 TCP client, JavaFX gameplay and integration handoff

Base: `develop`. Head: `feature/m2-tcp-client`.
Status: local preparation only; feature publication and merge are held pending
successful team integration and verification.

M2 now provides persistent protocol TCP transport, immutable client state,
asynchronous services, console interaction and JavaFX gameplay. It preserves
server authority, validates delayed actions and shuts down owned workers.
Fetched develop is merged without conflicts, including the actual M5 classes.
The optional TLS verification script can exercise those built classes without
introducing a permanent M2-to-M5 dependency.

Validation: 281 M2 tests, 78 M4 tests, 308 GUI checks and 18 actual TLS checks pass.
The unmodified full reactor fails on the shared codec test. A separate owner
test run also finds two M5 failures and three errors. M1/M3/app remain skeletons;
real three-client gameplay and UDP/LAN acceptance cannot run. The passing TLS
probe uses a test gateway and does not replace M5's failing suite.

Do not merge until owner failures and missing production integration are resolved.
See FINAL_INTEGRATION.md for exact evidence, commands and pending acceptance.
