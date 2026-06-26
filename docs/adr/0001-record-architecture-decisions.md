# 1. Record Architecture Decisions

Date: 2025-05-26
Status: Accepted

## Context

OpenBank is a regulated banking platform. Every significant architectural
choice must be defensible to internal review, external auditors (Big-4,
QSA), and the regulator (CNB). Tribal knowledge is insufficient — decisions
must be written down with rationale, alternatives, and consequences at the
time of the decision.

## Decision

We will use **Architecture Decision Records (ADRs)** as described by
Michael Nygard, stored as Markdown files in `docs/adr/` and numbered
sequentially (`NNNN-title.md`).

Every ADR has the following sections:

- **Title** — short noun phrase
- **Status** — proposed | accepted | deprecated | superseded by ADR-NNNN
- **Context** — why this decision is needed, forces at play
- **Decision** — the change we are proposing or have agreed to
- **Consequences** — positive, negative, and neutral outcomes

An ADR is **required** when:

- Adding a new service or removing one
- Choosing a database, message broker, cache, or external SaaS
- Changing wire protocol, API contract, or event schema in a breaking way
- Changing security mechanism (auth, encryption, key management)
- Changing build, deploy, or release process
- Introducing a new programming language or framework
- Adding a new third-party dependency to a critical service
- Adopting or dropping a cross-cutting pattern (outbox, saga, CQRS, ...)

ADRs are **immutable once accepted**. To change a decision, write a new
ADR with `Supersedes: ADR-NNNN` in the header, and update the old ADR
status to `Superseded by ADR-MMMM`.

## Consequences

**Positive**
- Traceability for auditors: every change to architecture has a written
  rationale at the time of decision.
- Onboarding is faster: new engineers can read ADRs to understand why
  things are the way they are.
- Decision quality improves: writing things down forces clarity.

**Negative**
- Overhead per significant decision (typically 30 min to write).
- Risk of ADR rot if not enforced via PR review.

**Mitigation**
- ADR template in this repo.
- PR template asks whether an ADR is needed.
- CODEOWNERS requires architect review for `docs/adr/`.

## References

- Michael Nygard, "Documenting Architecture Decisions" (2011)
- https://adr.github.io
