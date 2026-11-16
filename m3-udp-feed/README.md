# M3 - UDP Discovery and Live Feed

| | |
|---|---|
| **Owner** | Member 3 |
| **Concept** | UDP (DatagramSocket, DatagramPacket, broadcast, multicast) |
| **Plan** | Part 3 of [plan.md](../plan.md) |
| **Provides** | UdpServerAnnouncer, UdpServerFinder, UdpLiveFeedPublisher, UdpLiveFeedReceiver |
| **Consumes** | nothing (M4 pushes data into LiveFeedPublisher) |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- TCP game messages
- Reading room state directly (M4 pushes snapshots to you)
- TLS code

## Packages
- `com.quizbattle.udp`: M3: UDP. Server discovery (announcer/finder) and live feed (publisher/receiver), datagram codec.
