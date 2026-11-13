# OpenBank Roadmap

> **Last verified against `main`: 2026-10-30.** The gated milestone plan with acceptance criteria lives in
> [`docs/strategy/09-roadmap-M1-M7.md`](strategy/09-roadmap-M1-M7.md) (its progress snapshot is dated
> 2026-07-06 and is historical); this page is the current overview. Per-decision delivery state is
> tracked machine-readably in [`docs/adr/index.json`](adr/index.json) (`delivery_status`).

Milestones are **outcome-oriented** and **gated** — none is "done" until every acceptance criterion is
verified. Effort is measured in engineer-weeks (a focused solo engineer), deliberately **without calendar
dates**: elapsed time depends on maintainer availability and community participation. Track progress by
milestone completion in releases, not by inferred dates.

| # | Name | Goal | Effort (eng-weeks) | Status |
|---|---|---|---|---|
| **M1** | Foundation hardening | Audit-ready, testable, contributor-friendly repo | 2–3 | ✅ Complete |
| **M2** | Resilience primitives | Outbox + Temporal workflows + idempotency everywhere; coverage ≥ 70% | 3–4 | 🟢 Largely done — coverage target open |
| **M3** | Compliance evidence | Every regulatory requirement mapped to demonstrable evidence | 2–3 | 🟡 Long tail |
| **M4** | Observability & ops | OTel everywhere; SLOs; chaos engineering begins | 2–3 | 🟢 Largely done |
| **M5** | Security baseline | OWASP ASVS L3 + SLSA L3 supply chain + independent pen-test | 3–4 | 🟡 Long tail |
| M6 | Multi-region active-passive | DR failover within 30 min (RTO ≤ 30m, RPO ≤ 5m) | 2–3 | 🔴 Not started |
| M7 | Multi-region active-active + scale | Sustain Tier-A workload; production chaos | 3–4 | 🔴 Not started |

**Current focus (Q4 2026): close the M2/M3/M5 long tail, deepen the domain breadth that landed in
Aug–Oct, and keep the governance estate honest.**

- **M1 (Foundation)** — complete: path-scoped CI, ruleset branch protection with required signatures,
  per-service SemVer via release-please (71 released components), 320 declared CI gates.
- **M2 (Resilience)** — transactional outbox in 58 modules with a shared SENT-row retention scheduler;
  Temporal in 20 modules (payments, FX, statements, lending, settlement, KYB, campaigns, pensions) with
  payment-worker deployment versioning (ADR-0355); every money-path creation POST idempotent on natural
  keys (ADR-0287–0300, 0330). Remaining: the ≥70 % coverage target (floors are ratchet-only).
- **M3 (Compliance)** — PSD2 XS2A, GDPR erasure, EUDI/PID, VoP, hash-chained audit trail, DORA ICT
  incident register now persisted (was in-memory, finding K3), AnaCredit/FINREP report generation, net-settlement
  ledger leg (ADR-0281), real sanctions/PEP list ingest. Remaining: live regulator submission,
  commercial AML vendor feeds, Financial-Crime-as-Code packs (ADR-0353/0354).
- **M4 (Observability)** — OTel + DomainMetrics fleet-wide, workflow-liveness gauges and alerts,
  Pyrra SLOs, GoAlert, browser RUM in admin UI (ADR-0274), FinOps dashboard (ADR-0316).
  Chaos engineering deferred to M6.
- **M5 (Security)** — cosign signing + SBOM attestation enforced by Kyverno admission, default-deny
  NetworkPolicies, OPA authz with build-time M2M write-grant control, PAN envelope encryption via
  OpenBao Transit (ADR-0262), NUL-byte/null-param input hardening found by API fuzzing. Remaining:
  ASVS L3 self-assessment, official SLSA L3 generator, recurring pen-test, PII role-masking in admin UI
  (finding K6).

### Delivered since the July snapshot (Aug–Oct 2026)

Treasury/ALM and risk-engine foundations (ADR-0313–0315), scheme-agnostic card processing (ADR-0283),
KYB legal-entity onboarding (ADR-0284), campaign journeys on Temporal (ADR-0263), loyalty/referral/
gamification, pension services, banking context graph with authorized hybrid retrieval (ADR-0303–0309),
governed ClickHouse reporting (ADR-0286), catalog packs with approval policy as data (ADR-0345),
quarterly automated DR restore-verify, CNPG drain resilience (ADR-0325).

## Cross-cutting workstreams

Documentation, community building, governance evolution (maintainer → council → foundation), public-launch
preparation, release cadence, and funding all run **in parallel** with the milestones — see the strategy
doc for the full matrix.

## Public-launch trigger

Done — the repository went public on 2026-06-30 with private vulnerability reporting via GitHub Security
Advisories (see [SECURITY.md](../SECURITY.md)).

## Explicitly out of scope (all milestones)

OpenBank distributes **software**; it does not run a SaaS bank, hold a banking licence, or join payment
schemes directly — operators do. AI-driven account-opening / payment decisions stay experimental in
`openbank-agent-service` and `openbank-copilot-service` only. See the strategy doc for the full list.

---

## Known gaps (honest list)

Current limitations, verified 2026-10-30:

- **Interbank rails do not connect to live networks.** ISO 20022 pipeline and clearing simulator are
  wired and flags are on (ADR-0104/0108), and cleared batches post a net-settlement ledger leg
  (ADR-0281); money moves end-to-end with a simulated counterparty. Real SEPA/SWIFT/CERTIS network
  connections are not built.
- **Customer app is not GA.** A Kotlin Multiplatform + Compose customer app (iOS + Android) is in
  active development in a separate repo (`JiRaska/openbank-app`); the `openbank-customer-edge` BFF is
  deployed with OPA enforce mode on, but app stores releases are not yet public.
- **No commercial KYC/AML vendor integration** — screening logic is real (pg_trgm fuzzy matching) and
  sanctions/PEP lists are ingested from public sources (OFAC, UN, HM Treasury, PEP), but no commercial
  provider (Refinitiv, ComplyAdvantage) is wired. KYB ownership graph (ADR-0305) is planned.
- **Regulatory-reporting services generate reports but do not submit to a live regulator.**
  `finrep`/`anacredit` build the CNB/EBA reports; no live supervisory endpoint transmission.
- **SCA is maturing, not complete** — passkey RP, settlement gate and non-repudiation hash chain are
  in (ADR-0086), but full FIDO2 attestation / real OTP delivery are not finished.
- **AI copilot is sandbox-only** — a real LLM (deepseek-ai/DeepSeek-V3.2, via DeepInfra) runs in the
  sandbox copilot-service; the backend is model-agnostic (swap by env). Production model gateway,
  rate-limiting, and abuse guardrails are not hardened for public traffic.
- **Contract tests cover the critical paths, not everything** — 132 committed pacts, every one replayed
  by its provider on each PR (`check-pact-provider-replay.py`); many service pairs still have none.
- **Single region; RTO/RPO not demonstrated at failover scale** — money-path CNPG clusters run
  `instances: 2` ([ADR-0159](adr/0159-cnpg-ha-money-path.md)); non-money-path databases are single
  instance. `dr-restore-verify` now runs quarterly, but multi-region failover (M6) is not built.
- **Admin UI shows unmasked PII** — role-based PII masking (finding K6) is not wired in the backoffice.
- **Treasury, card processing, KYB and context-graph domains are partial** — see each ADR's
  `delivery_status`.
- **Not licensed to operate as a bank.** See the disclaimer in the README.

---

For acceptance criteria, verification steps, and rationale per milestone, read the full plan:
[`docs/strategy/09-roadmap-M1-M7.md`](strategy/09-roadmap-M1-M7.md).
