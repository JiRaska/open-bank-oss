# OpenBank Roadmap

> The authoritative, gated milestone plan lives in
> [`docs/strategy/09-roadmap-M1-M7.md`](strategy/09-roadmap-M1-M7.md). This page is the short overview.

Milestones are **outcome-oriented** and **gated** — none is "done" until every acceptance criterion is
verified. Effort is measured in engineer-weeks (a focused solo engineer), deliberately **without calendar
dates**: elapsed time depends on maintainer availability and community participation. Track progress by
milestone completion in releases, not by inferred dates.

| # | Name | Goal | Effort (eng-weeks) |
|---|---|---|---|
| **M1** | Foundation hardening | Audit-ready, testable, contributor-friendly repo | 2–3 |
| M2 | Resilience primitives | Outbox + saga + idempotency everywhere; coverage ≥ 70% | 3–4 |
| M3 | Compliance evidence | Every regulatory requirement mapped to demonstrable evidence | 2–3 |
| M4 | Observability & ops | OTel everywhere; SLOs; chaos engineering begins | 2–3 |
| M5 | Security baseline | OWASP ASVS L3 + SLSA L3 supply chain + independent pen-test | 3–4 |
| M6 | Multi-region active-passive | DR failover within 30 min (RTO ≤ 30m, RPO ≤ 5m) | 2–3 |
| M7 | Multi-region active-active + scale | Sustain Tier-A workload; production chaos | 3–4 |

**Current focus: M1 — Foundation hardening.** Bring the repo from "code dump" to "audit-grade reference
implementation": all modules build green, CI gates (build/test/lint/SAST/SBOM/license/gitleaks/OpenAPI),
branch protection on `main`, test scaffolding per service, and a signed `v0.1.0-alpha` release.

## Cross-cutting workstreams

Documentation, community building, governance evolution (maintainer → council → foundation), public-launch
preparation, release cadence, and funding all run **in parallel** with the milestones — see the strategy
doc for the full matrix.

## Public-launch trigger

The repo flips from private to public when M1 is complete, the security disclosure programme is wired, the
git history is secret-free (gitleaks-confirmed), and the public-facing docs are polished. Earliest realistic
launch is end of M1; latest sensible is end of M3 (compliance evidence lowers regulator FUD).

## Explicitly out of scope (all milestones)

OpenBank distributes **software**; it does not run a SaaS bank, hold a banking licence, or join payment
schemes directly — operators do. AI-driven account-opening / payment decisions stay experimental in
`openbank-agent-service` only. See the strategy doc for the full list.

---

For acceptance criteria, verification steps, and rationale per milestone, read the full plan:
[`docs/strategy/09-roadmap-M1-M7.md`](strategy/09-roadmap-M1-M7.md).
