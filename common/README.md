# Common (shared contracts)

| | |
|---|---|
| **Owner** | Shared: all five members (changes only via a contract-change PR approved by all) |
| **Concept** | Shared contracts: protocol, interfaces, config |
| **Plan** | Part 0 (shared prerequisites) of [plan.md](../plan.md) |
| **Provides** | Message / MessageType / MessageCodec, contract interfaces, AppConfig |
| **Consumes** | nothing |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Putting any feature logic here
- Changing a signature after the contract freeze without all five approvals

## Packages
- `com.quizbattle.common.contract`: Interfaces that cross module boundaries (ClientSession, MessageHandler, TokenVerifier, LiveFeedPublisher, ...). FROZEN after the Phase 0 sign-off. See architecture.md section 6.
- `com.quizbattle.common.protocol`: Message, MessageType and MessageCodec for the line-based TCP wire format. See docs/PROTOCOL.md.
- `com.quizbattle.common.model`: Tiny shared value types, e.g. ServerInfo.
- `com.quizbattle.common.config`: AppConfig: loads config/*.properties with environment-variable overrides for secrets.
