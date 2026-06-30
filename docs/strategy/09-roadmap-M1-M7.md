# Roadmap M1-M7

> Last updated: 2026-05-29
> Status: **v0.1** — proposed roadmap. Subject to revision after community feedback and as constraints surface.
> Each milestone is **gated** by explicit acceptance criteria; no milestone is "done" until every criterion is verified.

## Progress snapshot — 2026-05-29

Verified against code, not against commit messages. Legend: `[x]` done · `[~]` partial / in progress · `[ ]` not started.

| Milestone | Completion | Headline |
|---|---|---|
| M1 Foundation hardening | **~60%** | CI + security scanning + build green; missing contracts module, FE tests, signed release |
| M2 Resilience primitives | **~45%** | Outbox + idempotency centralised; saga-framework decided (ADR-0045: custom lib, not Temporal/Axon) + shared `SagaStateMachine` primitive in libs; still **1 of 3 sagas**; coverage tooling now live in libs (Kover, regression-floor gate — ADR-0020), not yet rolled out to services |
| M3 Compliance evidence | **~15%** | CDE NetworkPolicy + AsyncAPI docs only; regulatory scores still low |
| M4 Observability & ops | **~15%** | OTel deps wired + tech-inventory UI + BCP health gate; no dashboards/SLOs/chaos |
| M5 Security baseline | **~25%** | CycloneDX SBOM, SHA-pinned actions, Istio mTLS, Vault/OPA *plans*; no cosign/SLSA/pen-test |
| M6 Multi-region A/P | **0%** | not started |
| M7 Multi-region A/A | **0%** | not started |

### Critical audit findings (K1–K7 from 2026-05-28) — current state

| # | Finding | Status |
|---|---|---|
| K1 | Hardcoded DB/Redis creds in account-service | **✅ FIXED** — now `${POSTGRES_PASSWORD:CHANGE_ME_LOCAL_DEV_ONLY}` env var |
| K2 | SCA push/biometric verify always `true` (PSD2 RTS bypass) | **⏳ DESIGNED** — `ScaService.kt:85` still `PUSH_NOTIFICATION, BIOMETRIC -> true`; fix designed in ADR-0021 (fail-closed now + decoupled device-approval channel). Code change pending product sign-off (functional regression for those methods) |
| K3 | ICT incident log = `ConcurrentHashMap` (DORA) | **❌ OPEN** — `IctIncidentService.kt:34` still in-memory (outbox *is* now persisted) |
| K4 | Zero K8s NetworkPolicy + no service-to-service auth | **✅ FIXED** — `network-policies.yaml` (default-deny) + `istio.yaml` (STRICT mTLS + JWT) |
| K5 | GDPR anonymize → linkable `deleted-<UUID>@erased.invalid` | **✅ FIXED** — `PartyRepositoryImpl.kt:44` now `erased-<randomUUID>@erased.invalid` (unique, not derived from partyId) |
| K6 | Admin UI shows raw PII, no role masking | **❌ OPEN** — `parties/page.tsx` renders `{p.email}` raw; `PiiMask` lib exists in backend only |
| K7 | `AuditResource` is `@PermitAll` | **✅ FIXED** — `AuditResource.kt` now `@RolesAllowed("ROLE_AUDITOR","ROLE_ADMIN","ROLE_COMPLIANCE")` + annotation-contract regression test |

**4 of 7 critical findings resolved (K1, K4, K5, K7).** K7 (audit `@PermitAll` → role-gated) and K5 (GDPR tombstone email de-linked from partyId) fixed 2026-05-29. K2 is **designed** (ADR-0021: fail-closed + decoupled device-approval channel) but its code change is a functional regression needing product sign-off, so it is deferred deliberately rather than half-done. Remaining open: **K3** (needs a Panache repo — pattern already exists in same service's outbox) and **K6** (UI wiring of the existing masking lib + role gate). These are the highest-leverage next steps before any public launch.

### What landed since the 2026-05-26 roadmap draft

- **openbank-libs refactor F1–F3** (ADR 0013/0014): shared outbox, typesafe IDs, common exception mappers, PII masking, roles, audit envelope, S2S token provider.
- **Stack upgrade** (scénář B): Quarkus 3.13 → 3.33.2 LTS, Kotlin 2.0 → 2.3.20, Gradle 8.8 → 9.5.1, JDK 21 → 25 + ZGC.
- **SBOM / tech-stack visibility** (SBOM-1…5): per-service CycloneDX, `BuildInfo` in `/api/v1/info`, `/system/inventory` UI + OSV.dev CVE markers.
- **Infra hardening**: NetworkPolicy default-deny, Istio STRICT mTLS, Vault adoption plan (ADR 0017), OPA sidecar plan (ADR 0018).
- **CI**: per-service pipeline, SHA-pinned actions, gitleaks/CodeQL/Trivy/Syft, UI tsc gate, per-service SBOM artefact job.
- **Docs**: per-service documentation renderer in Admin UI (Docs-1…3).
- **Coverage**: Kover added to `openbank-libs` with a regression-floor gate wired into `check`/`build` (ADR-0020); `SecurityContextExtensions` unit test added (libs/security 50% → ~59% line).
- **Critical-finding hardening**: K7 fixed (audit trail `@PermitAll` → `@RolesAllowed`, + annotation-contract regression test), K5 fixed (GDPR tombstone email de-linked from `partyId`), K2 designed (ADR-0021, SCA decoupled device approval).
- **Analytics/reporting layer**: decided event-fed ClickHouse medallion (bronze/silver/gold) over the existing outbox stream — **not** CDC, **not** a lakehouse (ADR-0022). Added `analytics.{AnalyticsEnvelope, AnalyticsProjections, AnalyticsRetention, Reconciliation, Backfill}` to libs (dedupe, last-writer-wins, as-of/SCD2, reconciliation diff, backfill planner — all unit-tested; 10-year bronze floor in code) and scaffolded the stateless `openbank-analytics-sink` service. Reporting adds **zero** OLTP read load. After a critical self-review, the classic batch-DWH recovery muscles are now first-class (ADR-0022 addendum): **backfill** from the durable source of record (outbox, *not* Kafka) for outages beyond Kafka retention, **initial-load** seed, **corrections** by higher version, **DLQ** quarantine (no more log-and-swallow), **as-of/SCD2** views, and **real reconciliation** (version-only drift diff, off-peak cron + `ROLE_ADMIN/AUDITOR` triggers). External clients (ClickHouse, outbox reader, recon readers) are `@Default` no-op stubs so it stays offline-buildable; all orchestration/decision logic is implemented and tested. A second critical self-review raised **9 regulatory findings** (CNB/EBA/DORA/GDPR/BCBS 239); all are now closed to GREEN or YELLOW, none RED (ADR-0023): tamper-evidence (`AnalyticsIntegrity` record-hash + Merkle anchors → `WormArchive`), **maker-checker** four-eyes on every reload (`Proposal` state machine; propose/approve/execute, self-approval → HTTP 409), count tie-out + signed reconciliation evidence, **completeness** gap detection (provably no lost event), per-category **retention + GDPR Art. 17 erasure** path (`RetentionPolicies` + `ErasureService`, statutory-hold refusal vs crypto-shred), **schema governance** (unknown schema → DLQ), **freshness/lag** Micrometer gauges + readiness health, and a **data-residency** startup guard. The 4 GREEN controls (F3/F8/F9 + hashes) need no external system; the 5 YELLOW are GREEN-in-logic with one documented `@Alternative` adapter each (S3 Object Lock, KMS, Apicurio, OLTP/warehouse readers). 42 analytics unit tests green, offline JDK 25.

## Reading the roadmap

- Milestones are **outcome-oriented**, not feature lists.
- Effort estimates are **engineer-weeks** assuming a single experienced engineer at full pace. Real elapsed time depends on availability and reviewer feedback.
- A milestone may overlap with the next if work streams are independent.
- Total elapsed time M1 → M7 estimated at **17-23 weeks** for a focused solo engineer; 6-9 months for a one-person side project.

## Milestone overview

| # | Name | Goal | Effort (engineer-weeks) |
|---|---|---|---|
| M1 | Foundation hardening | Make the repo audit-ready, testable, contributor-friendly | 2-3 |
| M2 | Resilience primitives | Outbox + saga + idempotency everywhere | 3-4 |
| M3 | Compliance evidence | Map every regulatory requirement to evidence; controls demonstrable | 2-3 |
| M4 | Observability and ops | OTel everywhere; SLOs; chaos starts | 2-3 |
| M5 | Security baseline | OWASP ASVS L3 + supply chain hardening + pen-test | 3-4 |
| M6 | Multi-region active-passive | DR drill; failover within 30 min | 2-3 |
| M7 | Multi-region active-active + scale | Tier-A workload sustained; chaos in production | 3-4 |

## M1 — Foundation hardening

### Goal

Bring the repo from "code dump" to "audit-grade reference implementation". A new contributor must be able to build, test, and ship a change within their first day.

### Acceptance criteria

- [x] `git init` + push to `github.com/JiRaska/open-bank-oss` (private repo, ready to flip public).
- [~] All 27 modules build green via `./gradlew build`. *(compileKotlin green across all 28 + libs tests 80/80; full `build` with every service's test task not yet gated in CI.)*
- [~] CI configured: GitHub Actions with build, test, lint, SAST, dependency scan, SBOM, license-check, gitleaks, OpenAPI lint. *(Have: CodeQL, Trivy, Syft+CycloneDX SBOM, gitleaks, Dependabot, UI tsc, per-service pipeline, SHA-pinned actions. Missing: OpenAPI lint, license-check gate.)*
- [~] Branch protection on `main`: required reviews, required checks, signed commits, no force push, no deletion. *(Idempotent ruleset script committed; not all rules verified active.)*
- [ ] DCO bot active; CONTRIBUTING enforced.
- [~] Test scaffolding in every JVM service (JUnit 5 + Kotest + Testcontainers); minimum smoke test per service. *(27/28 have ≥1 test; `openbank-agent-service` has 0.)*
- [ ] Frontend test scaffolding in every web app (Playwright + Vitest); minimum smoke test per app.
- [ ] OpenAPI 3.1 stubs in `openbank-contracts/` for every externally-exposed service (initially empty contracts; structure in place). *(No `openbank-contracts/` module exists yet.)*
- [~] AsyncAPI 3.0 stubs for every Kafka topic. *(`docs/asyncapi/` exists; per-topic coverage unverified.)*
- [~] Issue templates, PR template, security policy verified. *(SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md present; issue/PR templates unverified.)*
- [ ] First release `v0.1.0-alpha` tagged with signed tag, SBOM attached, container images signed with cosign. *(No git tags yet.)*
- [ ] Public-launch checklist drafted in `docs/governance/public-launch-checklist.md`. *(No `docs/governance/` dir.)*

### Out of scope for M1

- Actual saga implementations
- Cross-region replication
- PCI DSS certification

### Verification

- CI green on `main`.
- Fresh clone → `./gradlew build && pnpm install && pnpm build` works on macOS, Linux, GitHub Actions runner.
- New contributor (volunteer) ships a typo-fix PR within first hour.

## M2 — Resilience primitives

### Goal

Every multi-service workflow runs on outbox + saga + idempotency. The reference implementation demonstrates that the platform handles partial failures correctly.

### Acceptance criteria

- [~] Outbox table + Debezium CDC wired in every service that publishes Kafka events. Currently only `sepa-payment`; target 100% coverage of event-publishing services. *(Shared outbox primitives in `openbank-libs` (ADR 0013) + per-service `OutboxDispatcher` across services. **Deviation:** transactional-outbox + poller dispatch, not Debezium CDC — revisit whether to adopt CDC or amend the criterion.)*
- [x] Saga library chosen (Axon, custom, or Temporal embedded); documented in ADR-0045. *(Decision: lightweight **custom** primitive in `openbank-libs`, not Temporal/Axon — rationale: operability, audit/CDE scope, consistency with `CaseTransitionEngine` + outbox poller. First primitive `SagaStateMachine<S>` landed in `libs/domain/saga` and `PaymentSaga` migrated onto it (IT green). Step+executor for multi-step sagas deliberately deferred to the first multi-step saga, per ADR-0045.)*
- [~] At least 3 sagas implemented end-to-end:
  - [x] Domestic/transaction payment saga — `PaymentSagaOrchestrator` (state machine now via shared `SagaStateMachine` primitive + Panache persistence + compensation branch + WireMock IT, both tests green). **Thin:** only the ledger posting has external side effects.
  - [ ] Account opening saga (party + kyc + account + notification) — account-service only emits outbox events; no orchestration.
  - [ ] SEPA SCT saga (party + ledger + sepa-payment + notification) — no orchestration in sepa-payment.
- [~] Idempotency keys mandatory on all POST/PUT/PATCH endpoints; framework-level enforcement. *(`RedisIdempotencyStore` centralised in `openbank-libs`; per-endpoint enforcement not yet universal.)*
- [ ] Property-based tests on ledger arithmetic (Kotest property tests).
- [ ] Saga state machines covered by tests including compensation paths.
- [ ] Integration tests with Testcontainers for every service.
- [~] Code coverage gate ≥ 70% on application + domain layers. *(Kover now wired in `openbank-libs` with a **regression-floor** gate in `check`/`build` — ADR-0020. libs sits at ~40% line; the ≥70% target and per-service rollout (via `build-logic/` convention plugin, Fáze 4) are still outstanding. Coverage is now measurable — the original "nothing shows in coverage" cause (no tooling) is resolved.)*

### Verification

- Saga test suite passes with fault injection.
- Reconciliation job runs in CI on test data; produces zero discrepancies.
- Coverage report attached to release.

## M3 — Compliance evidence

### Goal

For every regulatory requirement in the compliance matrix, produce evidence an auditor can accept. The platform is **demonstrably** compliant, not merely "designed for" compliance.

### Acceptance criteria

- [ ] Every row in `docs/strategy/07-compliance-matrix.md` has a verification artefact (test, screenshot, log sample, or attestation).
- [ ] PSD2 sandbox: AISP and PISP endpoints functional against EBA conformance suite.
- [ ] SCA implementation passes EBA SCA test cases.
- [ ] Audit log demonstrably tamper-evident (append-only, hash-chained).
- [ ] GDPR DSAR endpoint implemented end-to-end (export + erase).
- [ ] AML transaction monitoring with at least 5 reference rules.
- [ ] DORA major-incident reporting template populated with sample data.
- [ ] PCI DSS scope diagram + CDE segmentation documented and enforced by NetworkPolicy.
- [ ] Compliance dashboard (Grafana) showing real-time evidence freshness.

### Verification

- External compliance reviewer (not maintainer) signs off on evidence package.
- EBA conformance test report attached.

## M4 — Observability and operations

### Goal

The platform is operationally legible. No silent failures. SLOs defined and measured. Chaos engineering programme begins.

### Acceptance criteria

- [ ] OpenTelemetry (traces, metrics, logs) emitted by every service with no gaps.
- [ ] Grafana dashboards per service: RPS, error rate, latency p50/p95/p99, saturation, top errors.
- [ ] SLO defined per service tier (matching `docs/strategy/05-resilience-design.md`).
- [ ] Alertmanager wired with runbook URL in every alert.
- [ ] Synthetic probes for golden flows (login, balance check, payment initiate).
- [ ] Chaos Mesh / LitmusChaos running in staging with at least 5 experiment types (pod kill, network latency, DNS chaos, disk fill, CPU pressure).
- [ ] Incident response runbook drafted for top 10 scenarios.
- [ ] Quarterly tabletop drill executed; output documented.
- [ ] k6 nightly load tests in CI with regression gates.

### Verification

- Tabletop drill executed and minutes published.
- Chaos experiments pass without incident.
- Load test pass within latency budgets from `docs/strategy/06-scalability-targets.md`.

## M5 — Security baseline

### Goal

Reach OWASP ASVS Level 3 baseline. Supply chain hardened to SLSA Level 3. Independent pen-test conducted.

### Acceptance criteria

- [ ] OWASP ASVS L3 self-assessment completed with documented evidence per requirement.
- [~] All controls from `docs/strategy/04-security-baseline.md` Status target = Required are implemented and verified. *(mTLS via Istio STRICT + NetworkPolicy default-deny landed; WAF/OPA still plan-stage (ADR 0018); Vault plan-stage (ADR 0017).)*
- [ ] Container images signed with cosign; verification enforced at admission.
- [x] SBOM published per release in CycloneDX format. *(Per-service `cyclonedxBom` + `sbomAll` aggregate + CI upload job, 90-day retention.)*
- [ ] SLSA Level 3 build provenance attestation per release.
- [ ] Independent pen-test conducted (external provider); critical findings remediated.
- [ ] Bug bounty programme drafted (low-budget acceptable: hall of fame + swag).
- [ ] CIS K8s Benchmark 1.9 passes via kube-bench in CI.
- [ ] FAPI 2.0 conformance test passes for PSD2 endpoints.

### Verification

- Pen-test report (with critical findings closed) attached to release.
- ASVS L3 evidence package reviewable by external auditor.

## M6 — Multi-region active-passive

### Goal

The platform survives loss of an entire cloud region. DR failover within 30 minutes.

### Acceptance criteria

- [ ] Reference deployment topology covers two regions: primary + cold standby.
- [ ] Postgres async cross-region replication wired for all Tier 0-2 services.
- [ ] Kafka cross-region mirroring (MirrorMaker 2) wired for all banking topics.
- [ ] Object storage cross-region replication enabled.
- [ ] DNS failover automation (Route 53 / Cloud DNS) with documented manual approval gate.
- [ ] DR runbook: failover and failback procedures.
- [ ] Full DR drill executed end-to-end: RTO ≤ 30 min, RPO ≤ 5 min achieved against staging.
- [ ] Recovery from cold backup tested (worst case): RTO ≤ 4 h.
- [ ] DORA TLPT (Threat-Led Pen-Test) framework documented for operators required to perform it.

### Verification

- Recorded DR drill: failover in N minutes; failback in M minutes; data loss windowed at K seconds.
- DR drill repeated by a different operator in the community (validation that runbook is operator-executable, not maintainer-tribal-knowledge).

## M7 — Multi-region active-active + Tier-A scale

### Goal

The platform sustains Tier-A workload (5M customers, 10M daily payments) with active-active multi-region. Chaos engineering moves into production for non-Tier-0 services.

### Acceptance criteria

- [ ] Customer pinning to home region implemented; cross-region read replicas working.
- [ ] Conflict resolution for cross-region writes documented and tested.
- [ ] Sustained load test at Tier-A targets for 30 minutes with no SLO breach.
- [ ] Burst test at 4x Tier-A peak for 5 minutes; recovery within 30 seconds.
- [ ] Soak test at Tier-B sustained for 8 hours; no memory leak, no connection leak, no queue depth growth.
- [ ] Production chaos experiments running on Tier 2-3 services (notification, audit, interest first).
- [ ] Capacity model documented: cost per 1M customers, cost per 1M daily transactions.
- [ ] Horizontal sharding strategy for Postgres documented (per-customer-segment shards).
- [ ] First independent production deployment of OpenBank by an external operator publicly announced.

### Verification

- Tier-A load test report.
- External operator running OpenBank in production (case study published).

## Cross-cutting workstreams

These run in parallel with milestones, not sequenced after them.

| Workstream | M1 | M2 | M3 | M4 | M5 | M6 | M7 |
|---|---|---|---|---|---|---|---|
| Documentation (strategy, ADRs, runbooks) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Community building (Discord, mailing list, monthly call) | start | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Governance evolution (single maintainer → council → foundation) | document | recruit | recruit | council | council | council | foundation |
| Public launch (flip repo to public) | prepare | possible | recommended | — | — | — | — |
| Release cadence | alpha | beta | rc | 1.0-rc | 1.0 | 1.1 | 2.0 |
| Funding (grants, sponsorship, services) | — | apply | apply | active | active | active | active |

## Public launch trigger

The repo should flip from private to public when **all** of these are true:

- [ ] M1 complete (CI green, tests green, signed releases, governance docs in place)
- [ ] At least one external contributor confirmed to volunteer first PRs after launch
- [ ] Security disclosure programme wired (GitHub Security Advisories ready)
- [ ] License headers on all source files (already done — 416/416)
- [ ] Zero secrets in repo or git history (gitleaks confirms)
- [ ] README, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT polished for public consumption
- [ ] An initial blog post / announcement drafted explaining the project, the philosophy, the roadmap

Earliest realistic launch: **end of M1** (~2-3 weeks of focused work from today).
Latest sensible launch: **end of M3** (compliance evidence in place lowers regulator FUD).

## Out of scope across all milestones (intentional)

- Building a SaaS-hosted OpenBank (the project distributes software; SaaS is a separate commercial venture).
- Building a regulated bank ourselves (operators carry licences, not the project).
- Becoming a payments scheme participant directly (operators connect to schemes).
- AI-driven account opening or AI-driven payment decisions in production (experimental in `openbank-agent-service` only).
- Cryptocurrencies / tokenisation as a core product (could be a future extension).

## What this roadmap deliberately is not

- It is not a marketing plan.
- It is not a fundraising plan.
- It is not a hiring plan.
- It is not a guarantee — single-maintainer projects slip; the roadmap describes a credible path, not a contract.

## Disclaimer

All dates are absent on purpose. This roadmap measures effort in engineer-weeks because calendar time depends entirely on the maintainer's availability and on community participation. Operators planning to depend on OpenBank should track milestone completion as published in releases, not infer dates from this document.
