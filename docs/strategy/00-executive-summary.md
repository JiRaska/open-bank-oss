# OpenBank — Strategic Executive Summary

> Last updated: 2026-05-26
> Status: **Draft v0.1** — foundation document, subject to revision after team review.

## 1. What OpenBank is

OpenBank is an **open-source, cloud-native core banking platform** designed as a reference implementation of a modern retail bank that a licenced operator could deploy in production. It is licensed under **Apache-2.0** (permissive + patent grant), maximising adoption while letting operators embed proprietary modules (such as bank-specific risk models, AML rules, and regulatory adapters) without polluting the open core.

OpenBank targets the gap between:
- **Legacy core banking** (Temenos, Finacle, FIS) — mature but monolithic, expensive, slow to evolve.
- **Commercial cloud-native cores** (Mambu, Thought Machine Vault, 10x Banking) — modern but closed-source, vendor lock-in, EUR 1M+ annual licence fees.
- **Existing OSS attempts** (Apache Fineract, Mifos) — primarily microfinance-oriented, dated tech stack (Java EE), limited European retail banking coverage.

## 2. Positioning statement

> OpenBank is a cloud-native, event-sourced, BIAN-aligned core banking platform released under a permissive open-source licence, targeting European retail banking with a PSD2-, DORA-, and 5AMLD-oriented architecture from the outset (designed around these frameworks — not certified, audited, or a substitute for your own compliance review).

## 3. Strategic pillars

### 3.1. **Open by default, proprietary by exception**
The open core covers customer onboarding, accounts, ledger, payments, PSD2 APIs, and operational tooling. Bank-specific modules (proprietary risk models, AML scoring, regulatory reporting adapters) are intended to be built on top, not inside.

### 3.2. **Compliance-as-code**
Regulatory requirements are encoded as executable artefacts:
- Audit trail is event-sourced and tamper-evident.
- SCA flows are testable end-to-end.
- KYC/AML records are immutable, queryable, and exportable.
- Privacy by design: PII is encrypted at rest with rotated keys, access is logged.

### 3.3. **BIAN-aligned service boundaries**
Service domains follow the BIAN Service Domain Catalog v13. Naming and capability mapping is documented in `01-bian-service-domain-mapping.md`.

### 3.4. **Event sourcing where it matters**
The ledger is event-sourced with append-only journal entries. Other services use the outbox pattern for at-least-once event publishing. No event is ever lost, no transaction is silently dropped.

### 3.5. **Operational resilience by design**
Built for DORA from inception: bulkheads, circuit breakers, graceful degradation, chaos engineering plan, RTO/RPO targets per service, third-party dependency mapping.

### 3.6. **Brutal observability**
OpenTelemetry traces every domain operation. Logs are structured, correlated, and queryable. SLI/SLO defined per service. Black-box and white-box monitoring co-exist.

## 4. Differentiation vs. competitors

See `02-competitor-analysis.md` for the full matrix. Headline differentiators:

| Dimension | OpenBank | Fineract | Mambu | Thought Machine |
|---|---|---|---|---|
| Licence | Apache-2.0 | Apache 2.0 | Commercial | Commercial |
| Language | Kotlin | Java (legacy EE) | Java/Scala (closed) | Python smart-contracts |
| Architecture | Event-sourced microservices | Modular monolith | Microservices (SaaS-only) | Smart-contracts on Vault |
| BIAN alignment | Yes (v13) | Partial | Partial | Partial |
| Event sourcing in ledger | Yes (shipped) | No | No | Yes |
| PSD2 out-of-the-box | Yes | No | Partial | Partial |
| DORA-ready | Yes (by design) | No | Partial | Partial |
| Self-hosted | Yes | Yes | No (SaaS-only) | Yes (private cloud) |
| Annual licence cost | EUR 0 | EUR 0 | EUR 1M+ | EUR 1M+ |

## 5. Realistic maturity (as of 2026-05-26)

```
 Idea  →  PoC  →  Prototype  →  MVP  →  Alpha  →  Beta  →  GA  →  Bank-Licence
                                  ↑
                            You are here
```

- **Architectural intent**: ★★★★ (BIAN-aligned, event-driven, hex architecture, K8s-native)
- **Implementation depth**: ★★★★ (services implemented + deployed across the fleet; hexagonal; fleet-wide outbox + saga/Temporal orchestration; test suites with a ratchet-only coverage gate)
- **Operational maturity**: ★★★ (Docker stack, observability, K8s/Helm/ArgoCD scaffolds)
- **Compliance-readiness**: ★ (the work is ahead, not behind)
- **Security hygiene**: ★★★★★ (0 leaks, Apache-2.0 SPDX headers, signed commits required, branch protection enforced)
- **OSS-readiness**: ★★★★★ (LICENSE, README, CONTRIBUTING, CoC, SECURITY all in place)

## 6. Roadmap headline

Detailed in `09-roadmap-M1-M7.md`. Headline timeline assumes one full-time AI-augmented engineer:

| Milestone | Scope | Realistic ETA |
|---|---|---|
| M1 | Ledger event-sourced + 100+ tests + invariants | 2-3 weeks |
| M2 | Outbox refactor across all event-publishing services | 1-2 weeks |
| M3 | Saga orchestration (payment → ledger → notification) | 2 weeks |
| M4 | OpenAPI contracts for all 26 services | 1-2 weeks |
| M5 | Pact contract tests for top 10 inter-service flows | 2 weeks |
| M6 | PSD2 end-to-end sandbox with reference TPP | 3-4 weeks |
| M7 | Public MVP demo: two accounts, end-to-end payment, statements | 6-8 weeks from M1 start |

**Total realistic critical path: 17-23 weeks (4-5.5 months) from M1 start to MVP demo.**

For comparison, the 2024-2025 commercial benchmark for green-field core banking implementation is 18-36 months with teams of 50-100 engineers. OpenBank achieves a comparable scope in a fraction of the time precisely because it starts from a well-defined, opinionated foundation and uses modern AI-augmented development.

## 7. Risks (top 5; full register in `08-risk-register.md`)

1. **Regulatory risk** — DORA, PSD2 RTS-SCA, and CNB requirements evolve; the platform must keep pace.
2. **Concentration risk** — One maintainer (currently) is a bus-factor of 1.
3. **Adoption risk** — OSS projects without commercial backing die; community must grow before the maintainer burns out.
4. **Compliance trap** — "Open-source" does not mean "regulator-blessed"; an operator still needs licensing.
5. **AI-slop risk** — AI-generated code without rigorous tests and reviews can ship subtle bugs into critical paths.

## 8. Non-goals (deliberately out of scope)

- **OpenBank is not a bank.** It does not hold a banking licence, does not custody customer funds, does not operate any production system. It is a platform someone else may deploy.
- **OpenBank is not consulting.** The maintainer does not offer implementation services.
- **OpenBank is not a regulated SaaS.** The reference deployment runs locally for development only.
- **OpenBank is not legacy-compatible.** It will not provide adapters for COBOL mainframe cores or SWIFT FIN-only environments.

## 9. Decision authority

- **Architecture decisions** are recorded as ADRs in `docs/adr/`.
- **Strategic decisions** (licence, scope, partnerships) are recorded as amendments to this document.
- **Day-to-day decisions** are made by maintainers via PR review.

## 10. References

- `01-bian-service-domain-mapping.md` — BIAN alignment
- `02-competitor-analysis.md` — competitive landscape
- `03-technology-radar.md` — adopt / trial / assess / hold tech choices
- `04-security-baseline.md` — security controls
- `05-resilience-design.md` — DORA-aligned resilience patterns
- `06-scalability-targets.md` — performance/scale targets
- `07-compliance-matrix.md` — regulation-to-capability mapping
- `08-risk-register.md` — top 20 risks
- `09-roadmap-M1-M7.md` — milestone breakdown
