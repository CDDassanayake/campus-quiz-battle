# Project Report Outline (submit as PDF inside the ZIP)

Put the final PDF in `docs/` before creating the submission ZIP.

1. **Introduction**: the problem, the Campus Quiz Battle idea, team members and roles
2. **System overview**: architecture diagram, ports and channels, technology choices (copy and shorten from `architecture.md`)
3. **M1 - NIO gateway**: why NIO, selector loop design, framing and partial reads, screenshots, challenges
4. **M2 - TCP client and protocol**: protocol design, client threading, UI screenshots, challenges
5. **M3 - UDP discovery and live feed**: why UDP here, broadcast vs multicast, loss/ordering handling, screenshots
6. **M4 - Game core**: concurrency design, locking strategy, executor sizing, race conditions found and fixed
7. **M5 - Security**: TLS handshake, keystore and certificate, token design, mapping to threats (eavesdropping, MITM, tampering, replay, spoofing, DoS)
8. **Integration and testing**: integration steps done, test results, multi-machine test
9. **Limitations and future work**: e.g. game traffic not TLS-encrypted, scalability
10. **Appendix**: how to build and run, contribution table (who did what)

Each member writes their own section (3 to 7) following the same four sub-headings: *Concept, Design, Implementation highlights, Testing and problems*.
