# Roadmap M1-M7

> Last updated: 2026-07-06
> Status: **v0.2** — the repo went public on 2026-06-30, before M1 was fully complete by this
> document's own original criteria (see "Public launch trigger", below) — the plan has been
> overtaken by events more than once since v0.1 (2026-05-29). This revision re-verifies every
> checklist item against current code/gitops/CI state rather than assuming the v0.1 snapshot
> still holds. Subject to further revision as the actual (not planned) architecture keeps moving.
> Each milestone is **gated** by explicit acceptance criteria; no milestone is "done" until every criterion is verified.

## Progress snapshot — 2026-07-06

Verified against code, gitops, and CI config, not against commit messages or the previous
snapshot. Legend: `[x]` done · `[~]` partial / in progress · `[ ]` not started.

| Milestone | Completion | Headline |
|---|---|---|
| M1 Foundation hardening | **~75%** | CI/security/build gates all green and expanded well beyond the original list (34 workflows, ruleset-based branch protection, DCO enforced via required signatures); the originally-planned `openbank-contracts/` module was never built — superseded by a per-service `openapi.yaml` pattern instead (ADR-0048) — and there is no single `v0.1.0-alpha` tag, superseded by ~140 independent per-service release-please tags. Neither is "still missing," both are a different, shipped design. Genuinely still open: per-topic AsyncAPI stubs (one consolidated file exists, not per-topic), `docs/governance/public-launch-checklist.md` was never created (the same function is served by `public-readiness.yml` + `rules.yaml`, just not at that path) |
| M2 Resilience primitives | **~55%** | The saga framework this snapshot used to describe has been **superseded**: ADR-0045 (custom saga lib) is now `Superseded by ADR-0120`; `libs/domain/saga`/`SagaStateMachine` are gone from the repo (one enum remnant in transaction-service). Payment orchestration runs on **Temporal** instead (ADR-0101/0120): live in transaction-service, sepa-payment, domestic-payment; account-service and sepa-instant still have zero orchestration. Outbox is wired in **31 services** (was 1); idempotency directly wired in 8 services (lib available fleet-wide); a real Kotest property-test suite exists for ledger arithmetic; Kover coverage gate is rolled out to 40 services/modules with a ratchet floor of 39-40%, **not** the ≥70% this doc originally targeted (70% is now an explicit aspirational `money_path_target`, not yet reached) |
| M3 Compliance evidence | **~40%** | Real, functional progress the v0.1 snapshot didn't capture: PSD2 AISP/PISP endpoints work end-to-end (ADR-0090, Berlin Group), the SCA push/biometric bypass is fixed, the audit trail is tamper-evident (hash-chained, ADR-0133, distinct from the unrelated analytics-integrity feature), GDPR erasure is shipped fleet-wide (export is partial — party-service only, kyc/card-issuance PII pending, tracked in issue #268). Still genuinely thin: the compliance matrix (`07-compliance-matrix.md`) has no concrete evidence column, AML has 3 reference rules not ≥5 (and lives in fraud-service, not aml-service), DORA incident tracking is still an in-memory map (K3, still open), and no compliance dashboard exists |
| M4 Observability & ops | **~35%** | OTel wired in 33/36 services (gaps: anacredit, billing, finrep); one shared fleet Grafana dashboard exists (not per-service/templated as originally specced); a real Prometheus-rules SLO with burn-rate alerts exists only for Tier-1 payment rails, not fleet-wide; only ~21% of alert rules carry a runbook URL; synthetic probes check uptime/one API call, not the golden flows (login/balance/payment) by name; chaos engineering (ADR-0151) and nightly k6 load tests are both still explicitly Planned, not implemented |
| M5 Security baseline | **~45%** | Cosign image signing is shipped **and Enforce** in Kyverno admission (not Audit-mode as this doc used to say) — trust root, CI signing, and the policy are all live. SBOM is shipped on both axes (live per-service endpoint + `cosign attest --type cyclonedx`); a hand-built SLSA-shaped provenance document is attached per release, not the official SLSA L3 generator. One external pen-test has run (2026-06-10, admin UI) but isn't a recurring programme yet. Still not started: ASVS L3 self-assessment, bug bounty, kube-bench in CI, FAPI 2.0 conformance. Istio is still genuinely **not** installed in the live cluster (manifest exists, control-plane bootstrap never run, confirmed via ADR-0098's explicit "no service mesh deployed" statement) — this is the one "planned" label from the original doc that's still accurate as written |
| M6 Multi-region A/P | **0%** | not started — confirmed, no drift from the v0.1 snapshot |
| M7 Multi-region A/A | **0%** | not started — confirmed, no drift from the v0.1 snapshot |

### Critical audit findings (K1–K7 from 2026-05-28) — current state

| # | Finding | Status |
|---|---|---|
| K1 | Hardcoded DB/Redis creds in account-service | **✅ FIXED** — still `${POSTGRES_PASSWORD:CHANGE_ME_LOCAL_DEV_ONLY}` env var; the only literal secret left is under the `%test` profile for local Testcontainers, not production config |
| K2 | SCA push/biometric verify always `true` (PSD2 RTS bypass) | **✅ FIXED** (updated 2026-07-06 — was "designed, pending product sign-off") — `ScaService.kt:189-196` now routes `PUSH_NOTIFICATION, BIOMETRIC -> verifyDecoupled(...)`, requiring a signature-verified decision via `assertionVerifier.verify()`. No `-> true` shortcut remains. ADR-0021: Shipped |
| K3 | ICT incident log = `ConcurrentHashMap` (DORA) | **❌ OPEN** — `IctIncidentService.kt:36` still in-memory, unchanged since v0.1 |
| K4 | Zero K8s NetworkPolicy + no service-to-service auth | **✅ FIXED** — `network-policies.yaml` (default-deny) + per-component NetworkPolicies + `istio.yaml` (STRICT mTLS + JWT) manifest exists, though the Istio control plane itself has never actually been bootstrapped in the live cluster (see M5) — the NetworkPolicy half of K4 is unconditionally fixed regardless |
| K5 | GDPR anonymize → linkable `deleted-<UUID>@erased.invalid` | **✅ FIXED** — `PartyRepositoryImpl.kt:82-86` still `erased-<randomUUID>@erased.invalid` (unique, not derived from partyId) |
| K6 | Admin UI shows raw PII, no role masking | **❌ OPEN** — `parties/page.tsx:251` still renders `{p.email}` raw, unchanged since v0.1; `PiiMask` lib exists in backend only |
| K7 | `AuditResource` is `@PermitAll` | **✅ FIXED** — `AuditResource.kt` still `@RolesAllowed("ROLE_AUDITOR","ROLE_ADMIN","ROLE_COMPLIANCE")` + annotation-contract regression test |

**5 of 7 critical findings resolved (K1, K2, K4, K5, K7).** K2 (SCA bypass) shipped since the v0.1 snapshot — the product sign-off this doc said was pending happened. Remaining open, unchanged since 2026-05-29: **K3** (needs a Panache repo — pattern already exists in same service's outbox) and **K6** (UI wiring of the existing masking lib + role gate). These are still the highest-leverage next steps; note the repo went public in the interim without either being fixed (see "Public launch trigger" below).

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
- Total elapsed time M1 → M7 estimated at **17-23 weeks** for a focused solo engineer; 6-9 months for a one-person side project. *(Not re-estimated in the 2026-07-06 revision — the per-milestone percentages above moved substantially in the ~5-6 weeks since v0.1, faster than a naive read of the original estimate would suggest, but M6/M7 haven't started at all and the effort remaining there is unchanged. Treat this total as unverified rather than quietly still-correct.)*

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

- [x] `git init` + push to `github.com/JiRaska/open-bank-oss`. *(Updated 2026-07-06: the repo is now public, not "ready to flip" — flipped 2026-06-30, before the rest of this checklist was fully green; see "Public launch trigger" below.)*
- [x] All modules build green via `./gradlew build`. *(Updated 2026-07-06: 51 `openbank-*` directories now exist, 43 with a released `version.txt`; CI's per-service path-scoped pipeline gates build+test on every one. The module count itself was already stale at v0.1 — 27 was never re-verified as the fleet grew.)*
- [~] CI configured: GitHub Actions with build, test, lint, SAST, dependency scan, SBOM, license-check, gitleaks, OpenAPI lint. *(Updated 2026-07-06: 34 workflows now. Have: CodeQL, Trivy, Syft+CycloneDX SBOM, gitleaks (required check), Dependabot, `dependency-review.yml` with a `deny-licenses` gate (this now covers the "license-check" gap from v0.1), UI tsc, per-service pipeline, SHA-pinned actions, `api-fuzz.yml` validates each service's `openapi.yaml`. Still no dedicated spectral-style OpenAPI-lint job as originally specced, though schema validation happens via the fuzzing pipeline.)*
- [x] Branch protection on `main`: required reviews, required checks, signed commits, no force push, no deletion. *(Updated 2026-07-06: implemented as a GitHub ruleset, not classic branch protection — `main-protection` ruleset (active), enforces no-deletion, non-fast-forward, linear history, required signatures, PR-required (squash-only), and required status checks `all-green`/`Validate manifests`/`Gitleaks`/`issue-hygiene`.)*
- [x] DCO bot active; CONTRIBUTING enforced. *(Updated 2026-07-06: enforced via the ruleset's `required_signatures` rule rather than a dedicated DCO Action; `CONTRIBUTING.md` has a full DCO/sign-off section. Functionally equivalent to the original ask.)*
- [x] Test scaffolding in every JVM service (JUnit 5 + Kotest + Testcontainers); minimum smoke test per service. *(Updated 2026-07-06: `openbank-agent-service`, called out at v0.1 as having zero tests, now has 21 test files across contract/integration/unit layers.)*
- [x] Frontend test scaffolding (Playwright + Vitest); minimum smoke test per app. *(Updated 2026-07-06: `openbank-admin-ui` has both `playwright.config.ts` and `vitest.config.ts` wired with real test/e2e npm scripts. `openbank-app` — the customer-facing KMP app — lives in a separate repo, out of this checklist's scope.)*
- [ ] OpenAPI 3.1 stubs in `openbank-contracts/` for every externally-exposed service. *(Updated 2026-07-06: still not built as originally specced — `openbank-contracts/` doesn't exist. This turned out to be a design pivot, not a stalled task: every service instead carries its own `src/main/resources/openapi.yaml`, versioned independently per ADR-0048's two-axis model. Recommend closing this criterion as superseded rather than leaving it perpetually unchecked.)*
- [~] AsyncAPI 3.0 stubs for every Kafka topic. *(Updated 2026-07-06: `docs/asyncapi/` contains one consolidated `openbank-events.yaml`, not per-topic stubs — genuinely still a gap against the original per-topic ask.)*
- [x] Issue templates, PR template, security policy verified. *(Updated 2026-07-06: `.github/ISSUE_TEMPLATE/` has 5 templates (bug/feature/fleet-sweep/governance/config), `.github/PULL_REQUEST_TEMPLATE.md` exists, `SECURITY.md`/`CONTRIBUTING.md`/`CODE_OF_CONDUCT.md` all present and non-stub.)*
- [x] First release tagged, SBOM attached, container images signed with cosign. *(Updated 2026-07-06: superseded, not stalled — there's no single `v0.1.0-alpha` tag because the release model moved to ~140 independently-versioned per-service tags via release-please (`account-service-v0.11.4`, `ledger-service-v1.10.1`, etc). Every one of those releases does carry an attached SBOM and cosign-signed images, satisfying the intent even though the "one alpha tag" framing doesn't fit the shipped architecture.)*
- [ ] Public-launch checklist drafted in `docs/governance/public-launch-checklist.md`. *(Updated 2026-07-06: `docs/governance/` still doesn't exist. The same function — CI-enforced public-readiness gating — is served by `.github/workflows/public-readiness.yml` + `openbank-libs/governance/rules.yaml`, just not at the originally-named path. Low-value to build the standalone doc now that the check exists in code; consider closing this criterion as superseded too.)*

### Out of scope for M1

- Actual saga implementations *(Updated 2026-07-06: moot — payment orchestration moved to Temporal workflows instead of the saga framework this line assumed, see M2.)*
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

- [x] Outbox wired in every service that publishes Kafka events. *(Updated 2026-07-06: `OutboxDispatcher` now referenced in 31 services, up from 1 (`sepa-payment`) at v0.1. Still transactional-outbox + poller dispatch, not Debezium CDC — that deviation from the original criterion stands, and CDC was never revisited; treat the criterion as satisfied by the poller pattern, not by CDC.)*
- [x] Saga/orchestration framework chosen and shipped. *(**Updated 2026-07-06 — this whole criterion has been superseded, not just progressed.** ADR-0045 (custom `SagaStateMachine` lib, chosen over Temporal/Axon) is now `Status: Superseded by ADR-0120`. `libs/domain/saga` and `SagaStateMachine` are gone from the repo — one `SagaState` enum remnant survives in transaction-service, referenced only because `PaymentWorkflow.execute()`'s return type hasn't been renamed yet. The platform now runs payment orchestration on **Temporal** instead (ADR-0101, then ADR-0120 Phase 5+6, PR #17, retiring `PaymentSagaOrchestrator`). The original rationale for choosing custom-over-Temporal (operability, audit/CDE scope) was reversed once Temporal was adopted for other flows anyway.)*
- [~] Orchestration implemented end-to-end for the money-path services that need it:
  - [x] Transaction/domestic payment — `PaymentWorkflow`/`PaymentActivities` on Temporal (was the saga-based `PaymentSagaOrchestrator`, migrated).
  - [x] SEPA payment — `SepaPaymentWorkflow`/`SepaPaymentActivities` on Temporal.
  - [x] Domestic payment (separate service from transaction-service) — `DomesticPaymentWorkflow`/`DomesticPaymentActivities`/`WorkerRegistrar` on Temporal.
  - [ ] Account opening (party + kyc + account + notification) — account-service still only emits outbox events; zero orchestration, unchanged since v0.1.
  - [ ] SEPA Instant — zero orchestration, unchanged since v0.1 (note: this is a different service from SEPA payment above, which *is* done).
  - *(The original "at least 3 sagas: domestic/transaction, account-opening, SEPA SCT" framing doesn't map cleanly onto the shipped Temporal architecture — three DIFFERENT flows now have real orchestration than the ones originally named. Re-scope this checklist item next time it's touched instead of patching it again.)*
- [~] Idempotency keys mandatory on all POST/PUT/PATCH endpoints; framework-level enforcement. *(Updated 2026-07-06: `RedisIdempotencyStore` lives in `openbank-libs-runtime`, directly wired via `IdempotencyConfig` in 8 services (aml, sepa-payment, account, sca, consent, tpp-registry, domestic-payment, psd2). Still not universal per-endpoint enforcement across the whole fleet.)*
- [x] Property-based tests on ledger arithmetic (Kotest property tests). *(Updated 2026-07-06: `JournalEntryPropertyTest.kt` in ledger-service, `checkAll` over 300 iterations, 5 properties covering balance invariants, reversal symmetry, credit-positive booking deltas.)*
- [ ] Saga state machines covered by tests including compensation paths. *(Updated 2026-07-06: moot in its original form — there's no saga state machine left to test. Temporal workflow compensation-path test coverage would be the equivalent ask now; not separately verified in this pass.)*
- [ ] Integration tests with Testcontainers for every service.
- [~] Code coverage gate ≥ 70% on application + domain layers. *(Updated 2026-07-06: Kover is now rolled out fleet-wide — 40 services/modules opt in via the `build-logic` convention plugin, not just `openbank-libs` — with a **ratchet-only floor**, currently `39%` default / `40%` for money-path services (`rules.yaml: floors`). The ≥70% target survives only as an aspirational `money_path_target`, not yet reached anywhere. This is real, broad progress on rollout breadth; the actual coverage number is still well under the original target.)*

### Verification

- Saga test suite passes with fault injection.
- Reconciliation job runs in CI on test data; produces zero discrepancies.
- Coverage report attached to release.

## M3 — Compliance evidence

### Goal

For every regulatory requirement in the compliance matrix, produce evidence an auditor can accept. The platform is **demonstrably** compliant, not merely "designed for" compliance.

### Acceptance criteria

- [ ] Every row in `docs/strategy/07-compliance-matrix.md` has a verification artefact (test, screenshot, log sample, or attestation). *(Updated 2026-07-06: still not done — the matrix's 53 rows have prose "Verification" descriptions, not links to concrete artefacts. Genuinely no progress since v0.1 on the matrix itself, despite real progress on several of the underlying controls below.)*
- [x] PSD2 sandbox: AISP and PISP endpoints functional. *(Updated 2026-07-06: real and working — `openbank-psd2-service`'s `BerlinPisResource`/`BerlinConsentResource` + `openbank-consent-service`'s `ConsentResource` implement Berlin Group NextGenPSD2 (ADR-0090, Shipped). Not yet run against the actual EBA conformance suite, which is the one part of this criterion still open.)*
- [x] SCA implementation fixes the RTS bypass. *(Updated 2026-07-06: `ScaService.kt` no longer hardcodes push/biometric to `true` — see K2 above, ADR-0021 Shipped. Not yet run against EBA's formal SCA test cases, same caveat as PSD2 above.)*
- [x] Audit log demonstrably tamper-evident (append-only, hash-chained). *(Updated 2026-07-06: shipped — `openbank-audit-service` implements a SHA-256 hash chain with signed checkpoints (ADR-0133, Shipped 2026-06-29). Distinct from the unrelated `AnalyticsIntegrity`/`WormArchive` feature in the analytics-sink pipeline, which the v0.1 draft's "what landed" section conflated with this.)*
- [~] GDPR DSAR endpoint implemented end-to-end (export + erase). *(Updated 2026-07-06: erasure cascade is shipped fleet-wide (party/kyc/notification/card-issuance all handle `PartyErased`); export (Art. 15) only covers direct PII in party-service — kyc-service and card-issuance-service's contributions are still pending, tracked in issue #268. Automated retention/TTL enforcement is still policy-intent only, no scheduler exists.)*
- [~] AML transaction monitoring with at least 5 reference rules. *(Updated 2026-07-06: 3 rules exist (`BaselineAllowRule`, `VelocityH1ReviewRule`, `VelocityH24ReviewRule`, `RULE_VERSION=v2`) — not ≥5, and they live in `openbank-fraud-service`, not `openbank-aml-service` as this criterion assumed; `openbank-aml-service` is case-management only, no rule engine of its own.)*
- [~] DORA major-incident reporting template populated with sample data. *(Updated 2026-07-06: policy doc shipped (`docs/bcp/incident-response.md`, ADR-0146 Partial), but the incident register itself is still the in-memory `ConcurrentHashMap` from K3 — no persistence, no CI check, no admin-UI surface, no key-ceremony runbooks.)*
- [x] PCI DSS scope diagram + CDE segmentation enforced by NetworkPolicy. *(Confirmed unchanged since v0.1 — CDE-referencing NetworkPolicy/gitops YAML still present.)*
- [ ] Compliance dashboard (Grafana) showing real-time evidence freshness. *(Updated 2026-07-06: confirmed, still doesn't exist anywhere — no Grafana panel, no admin-ui compliance/evidence-freshness view.)*

### Verification

- External compliance reviewer (not maintainer) signs off on evidence package.
- EBA conformance test report attached.

## M4 — Observability and operations

### Goal

The platform is operationally legible. No silent failures. SLOs defined and measured. Chaos engineering programme begins.

### Acceptance criteria

- [~] OpenTelemetry (traces, metrics, logs) emitted by every service with no gaps. *(Updated 2026-07-06: 33/36 services wired; gaps are `anacredit`, `billing`, `finrep`.)*
- [~] Grafana dashboards per service: RPS, error rate, latency p50/p95/p99, saturation, top errors. *(Updated 2026-07-06: one shared fleet dashboard (33 real ConfigMaps, `dashboard-openbank-services.yaml`) grouped by service label — RPS/p99/error-rate/pod-count, not the per-service/templated dashboard originally specced. Saturation (CPU/heap/DB-pool) exists only in a dev-only docker-compose dashboard, never deployed to the sandbox.)*
- [~] SLO defined per service tier. *(Updated 2026-07-06: `05-resilience-design.md` defines DR tiers (RTO/RPO), explicitly *not* SLOs. A real numeric SLO with burn-rate alerting exists only for Tier-1 payment rails (`prometheus-rules-tier1.yaml`); `pyrra.yaml` itself admits other tiers aren't wired yet. No ADR maps tier→SLO fleet-wide.)*
- [~] Alertmanager wired with runbook URL in every alert. *(Updated 2026-07-06: only ~21% of alert rules (10/48) carry a runbook reference, and the highest-stakes ones — including `PaymentServiceDown` in the Tier-1 payment rules — have none. No AlertmanagerConfig CRD, just a Slack webhook.)*
- [~] Synthetic probes for golden flows (login, balance check, payment initiate). *(Updated 2026-08-09, ADR-0252 phase 2: the "not a real periodic monitor" half is fixed — `k6-synthetic.yaml`'s once-per-sync TestRun is replaced by `cronjob-journey-public-edge.yaml` on a 5-minute schedule, with failure and staleness alerts in `prometheus-rules-journey.yaml`. The flow-specific half is NOT: `journeys.yaml` declares `domestic-payment` and `push-delivery` as `planned` with their blockers (synthetic parties + taint, canary devices — #4348), so the gap is now declared rather than merely absent. Login is still uncovered beyond the OIDC-discovery blackbox probe.)*
- [ ] Chaos Mesh / LitmusChaos running in staging with at least 5 experiment types. *(Updated 2026-07-06: ADR-0151 (chaos engineering policy) is `Decision-Status: Accepted`, `Delivery-Status: Planned` — explicitly gated on the Temporal migration (M2) landing first, which it now has. Zero chaos tooling in gitops yet — this is now unblocked, not just "not started".)*
- [~] Incident response runbook drafted for top 10 scenarios. *(Updated 2026-07-06: ADR-0146 is `Delivery-Status: Partial`. `docs/bcp/incident-response.md` covers policy/severity; `docs/runbooks/` has 39 files, but 31 are auto-generated per-service scaffolds each flagging their own on-call-rotation/DR-drill gap, not a curated top-10 set. The referenced `docs/runbooks/key-ceremonies/` doesn't exist.)*
- [ ] Quarterly tabletop drill executed; output documented.
- [~] Scheduled k6 performance evidence and regression gates. *(Updated 2026-08-24: `.github/workflows/perf-gate.yml` runs a weekly scheduled and manually-dispatchable local smoke gate; it enforces k6 thresholds for its current `openbank-product-catalog` pilot and publishes a versioned Test Intelligence envelope. `.github/workflows/perf-baseline.yml` runs a separate weekly external money-path read baseline and publishes the same provenance shape. The remaining gap is material: neither lane is nightly, the smoke gate's declared scope is one non-money-path service, and the money-path baseline is advisory and explicitly skips without configured reachable targets. This is evidence, not a fleet-wide merge-blocking performance guarantee.)*

### Verification

- Tabletop drill executed and minutes published.
- Chaos experiments pass without incident.
- Load test pass within latency budgets from `docs/strategy/06-scalability-targets.md`.

## M5 — Security baseline

### Goal

Reach OWASP ASVS Level 3 baseline. Supply chain hardened to SLSA Level 3. Independent pen-test conducted.

### Acceptance criteria

- [ ] OWASP ASVS L3 self-assessment completed with documented evidence per requirement. *(Confirmed still not started, 2026-07-06.)*
- [~] All controls from `docs/strategy/04-security-baseline.md` Status target = Required are implemented and verified. *(Updated 2026-07-06, one correction from the previous note: NetworkPolicy default-deny is landed, but Istio's control plane has never actually been bootstrapped in the live cluster — `istio.yaml` itself documents `istioctl install` as an unmet prerequisite, and ADR-0098 explicitly states "no service mesh (Istio, Linkerd) is deployed." mTLS via Istio was **not** landed, contrary to what this line used to say. WAF/OPA (ADR-0018) and Vault (now OpenBao, per gitops) are further along than plan-stage — OPA enforcement is live for 13 non-money-path services (ADR-0034 Phase 5), and OpenBao is deployed (`openbao.yaml`, `openbao-config.yaml`).)*
- [x] Container images signed with cosign; verification enforced at admission. *(Updated 2026-07-06 — this is now genuinely shipped and Enforce, not just "planned": trust root chosen (AWS KMS `alias/openbank-cosign-signing`), every pushed image signed in `auto-deploy.yml`, and Kyverno's `verify-images-policy.yaml` image-signature rule is `validationFailureAction: Enforce` fleet-wide. The SBOM-specific admission rule — a second, separate `verifyImages` rule — remains planned; the signature rule alone is what's Enforce.)*
- [x] SBOM published per release in CycloneDX format. *(Confirmed unchanged since v0.1 — per-service `cyclonedxBom` + `sbomAll` aggregate + CI upload job. Also now served live per-service at `/q/openbank/sbom` and attested via `cosign attest --type cyclonedx`, both additions since v0.1.)*
- [~] SLSA Level 3 build provenance attestation per release. *(Updated 2026-07-06: a hand-built in-toto-shaped provenance document (`predicateType: https://slsa.dev/provenance/v1`) is generated and cosign-signed per release (`build-release-evidence.sh`), deliberately instead of the official `slsa-framework/slsa-github-generator`. Real evidence exists; the formal SLSA L3 conformance claim is still not made.)*
- [~] Independent pen-test conducted (external provider); critical findings remediated. *(Updated 2026-07-06: one external pen-test has run — `admin.open-bank.tech`, 2026-06-10 (ADR-0080, "Accepted, Partial") — findings remediated. Not yet a recurring programme; annual cadence + DORA TLPT are still planned-only.)*
- [ ] Bug bounty programme drafted. *(Confirmed still not present, 2026-07-06 — `SECURITY.md` has a vulnerability-disclosure process but no bounty/rewards mention.)*
- [ ] CIS K8s Benchmark 1.9 passes via kube-bench in CI. *(Confirmed still not started, 2026-07-06 — zero kube-bench references in `.github/workflows/`.)*
- [ ] FAPI 2.0 conformance test passes for PSD2 endpoints. *(Confirmed still not started, 2026-07-06 — only appears as a target in strategy/risk docs, no conformance-suite code.)*

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

**Updated 2026-07-06 — two rows have diverged from reality, not just progressed:**
- **Public launch already happened** (2026-06-30) — this row is retrospective for M1 now, not a future gate. See "Public launch trigger" below for which of its own preconditions were actually met at the time.
- **Release cadence never became a single alpha→beta→1.0 progression.** The repo ships ~140 independently-versioned per-service tags via release-please (`transaction-service-v1.12.3`, `standing-order-service-v0.11.2`, etc, `separate-pull-requests: true`), with no monorepo-wide version at all. There is no "current cadence stage" to report against this row — it's a structural mismatch with the actual per-service release model, not a status to fill in. Recommend replacing this row with "per-service release-please tags, no monorepo version" rather than continuing to patch a stage number that doesn't exist.
- **Governance evolution is still "single maintainer"** — a `CODEOWNERS` file exists at repo root, but no council/foundation structure has formed. No change from the v0.1 "document" stage in practice, three stages later than where the table's own M1 column placed it.

## Public launch trigger

> **Updated 2026-07-06: this section is now retrospective.** The repo flipped from private to
> public on 2026-06-30 — before this document's own M1-completion precondition was met, and
> before at least two of the other preconditions below were satisfied. Kept here as a record of
> what the plan required vs. what actually happened, not as a still-pending gate.

The repo should flip from private to public when **all** of these are true:

- [~] M1 complete (CI green, tests green, signed releases, governance docs in place). *(Not true at the time of the flip — M1 was still ~60% per the v0.1 snapshot. M1 is much closer to done now, retroactively, but the sequencing here was skipped.)*
- [ ] At least one external contributor confirmed to volunteer first PRs after launch. *(Still not true as of 2026-07-06 — `gh api repos/JiRaska/open-bank-oss/contributors` shows only the maintainer plus `github-actions[bot]`/`dependabot[bot]`; no human non-maintainer commits exist in git log.)*
- [~] Security disclosure programme wired (GitHub Security Advisories ready). *(`SECURITY.md` has a real disclosure process; whether the GitHub Security Advisories feature itself is enabled couldn't be confirmed directly — zero advisories filed either way.)*
- [x] License headers on all source files. *(The repo has grown to ~8,825 `.kt` files; 1 is missing an SPDX header (`VersionSingleSourceTest.kt`, a newer test file) — not the "416/416, already done" count this line used to cite, which was itself stale the moment the fleet grew past its original size.)*
- [x] Zero secrets in repo or git history (gitleaks confirms). *(Confirmed — enforced continuously via `secret-scan.yml` + `public-readiness.yml`, both required checks.)*
- [x] README, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT polished for public consumption. *(Confirmed non-stub quality.)*
- [ ] An initial blog post / announcement drafted explaining the project, the philosophy, the roadmap. *(Still not found anywhere in the repo, 2026-07-06.)*

**Bottom line: the repo launched with 3 of these 7 preconditions unmet or unverifiable** (M1 completion, external contributor, announcement draft). This isn't necessarily wrong for a solo maintainer's judgment call — but the roadmap that supposedly gated the decision didn't actually gate it. If this section is kept going forward, it should describe what "ready for a second contributor" or "ready for the next public milestone" looks like, not re-litigate a decision that's already made.

~~Earliest realistic launch: **end of M1** (~2-3 weeks of focused work from today).~~
~~Latest sensible launch: **end of M3** (compliance evidence in place lowers regulator FUD).~~
*(Both lines superseded — launch already happened, ahead of either estimate, without waiting for M3.)*

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
