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
- Code against the interfaces in `common`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Putting any feature logic here
- Changing a signature after the contract freeze without all five approvals

## Packages
- `com.quizbattle.common`: Shared contracts for all modules: Message, MessageType, MessageCodec, ServerInfo, the contract interfaces (ClientSession, MessageHandler, TokenVerifier, ...) and AppConfig. FROZEN after Part 0. See architecture.md section 6.
