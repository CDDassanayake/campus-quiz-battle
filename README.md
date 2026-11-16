# Campus Quiz Battle

A multiplayer quiz game for a campus LAN, built with plain Java network programming (`java.net`, `java.nio`, `javax.net.ssl`) for Assignment 02.

Players find a server automatically (UDP), log in securely (TLS), join a room on a non-blocking server (NIO) and answer timed questions while everyone sees a live countdown and leaderboard.

| Member | Module | Concept |
|---|---|---|
| M1 | `m1-nio-gateway` | Java NIO (Selector, SocketChannel, ByteBuffer) |
| M2 | `m2-tcp-client` | TCP sockets, protocol design, addressing |
| M3 | `m3-udp-feed` | UDP discovery, broadcast/multicast live feed |
| M4 | `m4-game-core` | Multithreading, concurrency |
| M5 | `m5-security` | TLS, keystore/truststore, hashing, signed tokens |

Start here: **[architecture.md](architecture.md)** (design and contracts) then **[plan.md](plan.md)** (tasks per member and integration steps). Wire format: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Prerequisites
- JDK 17 or newer (`java -version`, `keytool` must be on PATH)
- Maven 3.9 or newer
- Git

## Quick start (after the modules are implemented)

```bash
# 1. Secrets come from environment variables, never from files in Git
export QB_KEYSTORE_PASSWORD='choose-a-password'
export QB_TOKEN_SECRET='a-long-random-string-of-32-or-more-characters'
# Windows (cmd):  set QB_KEYSTORE_PASSWORD=...   set QB_TOKEN_SECRET=...

# 2. Create the server certificate (QB_EXTRA_SAN = the server's LAN IP so clients can verify it)
QB_EXTRA_SAN="ip:192.168.1.20" ./scripts/generate-keystore.sh      # Windows: scripts\generate-keystore.bat
# Copy secrets/truststore.p12 to every client machine (public certificate only)

# 3. Build and run
./scripts/run-server.sh      # on the server machine   (Windows: scripts\run-server.bat)
./scripts/run-client.sh      # on each player machine  (Windows: scripts\run-client.bat)
```

Ports: `5050` game (TCP/NIO), `5443` login (TLS), `5051` discovery (UDP), `5052` live feed (UDP). Allow Java through the firewall. All options are in `config/`.

## Repository layout
```
common/            shared contracts (frozen after Part 0)
m1-nio-gateway/    Member 1        m2-tcp-client/   Member 2
m3-udp-feed/       Member 3        m4-game-core/    Member 4
m5-security/       Member 5        app/             ServerMain / ClientMain wiring
config/  scripts/  docs/  secrets/ (git-ignored)
```

## Git workflow
- `main` = final, `develop` = integration, `feature/mN-...` = one branch per member (already created).
- Work only inside your own module folder, open PRs into `develop`.
- Do not change `common/` without all five members approving.

```bash
git checkout feature/m3-udp-feed     # your branch
git pull origin develop
git push origin feature/m3-udp-feed  # then open a PR into develop
```

## Troubleshooting
- **Server not found:** the Wi-Fi may block UDP broadcast. Set `udp.feed.mode=multicast`, type the server IP manually, or use a phone hotspot.
- **TLS handshake fails:** the keystore must include the server's LAN IP (`QB_EXTRA_SAN`), and the client needs the matching `truststore.p12`.
- **Port in use:** change the port in `config/*.properties` on both sides.
