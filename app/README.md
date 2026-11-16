# App (integration wiring)

| | |
|---|---|
| **Owner** | Integration driver of the current integration step (see plan.md) |
| **Concept** | Wiring only: ServerMain and ClientMain |
| **Plan** | Integration section of [plan.md](../plan.md) |
| **Provides** | ServerMain, ClientMain |
| **Consumes** | all modules |

## Rules for this folder
- Only the owner edits this folder. Work on your feature branch and open a PR into `develop`.
- Code against the interfaces in `common/contract`. Test with mocks inside this module's `src/test`.

## You must NOT do (outside your boundary)
- Feature logic (it belongs in the owning module)
- Editing outside integration/* branches

## Packages
- `com.quizbattle.app`: ServerMain and ClientMain: construct the modules and connect them through the contracts.
