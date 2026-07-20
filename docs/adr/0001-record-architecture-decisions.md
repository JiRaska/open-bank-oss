---
date: 2025-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, docs, architecture]
summary: "Significant architectural choices are recorded as Nygard-style Markdown ADRs in docs/adr, immutable once accepted and superseded only by a new ADR, so decisions stay defensible to auditors and the regulator."
---

# 1. Record Architecture Decisions

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

> **Amendment — machine-readable header and a digest tier.**
>
> The header above was prose, and by ~170 ADRs the fleet carried **four** coexisting
> conventions for it (`Status:`, `**Status:**`, a `| Field | Value |` table, and the
> later two-axis `Decision-Status:`/`Delivery-Status:`), plus an undocumented fifth
> delivery value (`Complete`). Every consumer needed its own fallback regex, and the
> generated index truncated statuses to 40 characters because values like
> `Accepted (2026-06-14 — decision implemented: …)` are paragraphs, not values.
>
> The header is now a **YAML front-matter block** defined by [SCHEMA.md](SCHEMA.md)
> and enforced by `.github/scripts/check-adr-registry.sh`: fixed keys, closed enums,
> a closed tag vocabulary ([tags.txt](tags.txt)), and supersession that must be
> recorded on **both** sides. The two status axes stay independent — `decision-status`
> is whether the decision stands, `delivery-status` is whether it was built.
>
> The block also carries a `summary` (≤240 chars). Those summaries are generated into
> [DIGEST.md](DIGEST.md), the whole decision history in ~16k tokens against ~400k for
> the fleet. That tier exists because the consequence below — "onboarding is faster:
> new engineers can read ADRs" — stopped being true at this scale: nobody, human or AI
> agent, loads 1.6 MB of Markdown, so a registry with no digest is a registry nobody
> reads. The honest claim is reach, not thrift: ~16k is a real cost, but it is a cost
> you *can* pay, and it buys the complete decision history rather than whichever three
> ADRs a grep happened to surface.
>
> Section requirements are unchanged and now checked (`## Context`, `## Decision`,
> `## Consequences`), except on superseded ADRs, which stay immutable historical
> records. `## Alternatives considered` and `## Compliance impact` are advisory
> warnings for now — 32 and 42 pre-schema ADRs respectively lack them, and
> back-filling is authoring work, not something a structural gate can force in one
> sweep. They graduate to errors under ADR-0144 once that backlog is closed.

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
