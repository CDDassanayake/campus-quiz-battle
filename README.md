# Campus Quiz Battle

A multiplayer quiz game for a campus LAN, built with plain Java network programming (`java.net`, `java.nio`, `javax.net.ssl`) for Assignment 02. Players find a server (UDP), log in (TLS), join a room on a non-blocking server (NIO) and answer timed questions with a live countdown and leaderboard.

| Member | Folder | Concept |
|---|---|---|
| M1 | `m1-nio-gateway` | Java NIO |
| M2 | `m2-tcp-client` | TCP sockets, protocol, addressing |
| M3 | `m3-udp-feed` | UDP discovery and live feed |
| M4 | `m4-game-core` | Multithreading, URL/URI |
| M5 | `m5-security` | TLS, certificates, hashing, tokens |

Read **[architecture.md](architecture.md)** (design, contracts), then **[plan.md](plan.md)** (tasks per member, integration steps). Message formats: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Requirements
JDK 17+ (with `keytool`), Maven 3.9+, Git.

## Build and run

```bash
# Secrets come from environment variables, never from Git
export QB_KEYSTORE_PASSWORD='choose-a-password'
export QB_TOKEN_SECRET='a-long-random-string-of-32-or-more-characters'
# Windows cmd:  set QB_KEYSTORE_PASSWORD=...   set QB_TOKEN_SECRET=...

# Server machine: create keystore + server-cert.pem (QB_EXTRA_SAN = this machine's LAN IP)
QB_EXTRA_SAN="ip:192.168.1.20" ./scripts/generate-keystore.sh     # Windows: scripts\generate-keystore.bat
# Copy secrets/server-cert.pem into the secrets/ folder of every client machine

mvn -q -DskipTests install                                          # build everything
mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ServerMain   # start the server
mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ClientMain   # start a client
```

(`app` runs only after `ServerMain` and `ClientMain` exist, at integration step I1.) Run a single module's tests with `mvn -q -pl m1-nio-gateway -am test`.

Ports: `5050` game (TCP/NIO), `5443` login (TLS), `5051` discovery (UDP), `5052` live feed (UDP). Options are in `config/`.

## Git workflow
`main` (final) ← `develop` (integration) ← `feature/mN-...` (one per member).
Work only in your own folder, `git pull origin develop` often, open PRs into `develop`. Details in plan.md.

## Troubleshooting
- **Server not found:** Wi-Fi may block UDP broadcast. Set `udp.feed.mode=multicast`, type the server IP, or use a phone hotspot.
- **TLS handshake fails:** the keystore must contain the server's current LAN IP (`QB_EXTRA_SAN`) and the client needs the matching `server-cert.pem`.
- **Port in use:** change it in `config/*.properties` on both sides.
- Allow Java through the firewall on the ports above.
