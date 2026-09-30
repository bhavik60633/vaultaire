# Architecture decision records

Each significant decision gets a short record: the context, the decision, the options rejected and the consequences. A record is never edited to change its decision. A new record supersedes it instead, so the history stays readable.

Every third-party dependency also needs a record before it is added (why it is needed, maintainer health, audit history, transitive dependencies, licence, and what breaks if it is compromised). The default answer is no.

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-stack.md) | Native Android UI over a shared Rust core | **Accepted** 2026-09-30 (Phase-0 spike) |
| [002](ADR-002-keyslots.md) | One random vault key wrapped in three keyslots | Proposed; for review at Gate G0 |

## Template

```markdown
# ADR-NNN: <decision in a few words>

- Status: Proposed | Accepted | Superseded by ADR-MMM
- Date: YYYY-MM-DD

## Context
## Decision
## Alternatives considered
## Consequences
## Revisit when
```
