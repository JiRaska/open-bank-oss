---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, observability, finops, resilience]
summary: "Credit runtime quality is contractual: named Pyrra SLOs (decision p99 < 200 ms via in-memory compiled policy, disbursement p99 < 2 s), per-point tracing, unit-cost showback and LLM budget caps with kill switch."
---

# ADR-0218 — Credit platform SLOs, scalability and FinOps guardrails

## Context

"Nejlepší na světě" is not a property of the ADR set — it is a property of **measured
runtime behaviour**: how fast a decision returns, how the platform behaves at 10×
applications, what a single originated loan costs. Without explicit numbers, these
degrade silently: a policy engine that re-reads tables from the DB per request, an
LLM agent with an open-ended budget, a money-path service with no SLO burn alert.

The platform already owns the instruments: SLO-as-code via Pyrra (ADR-0088), the
three-pillar observability stack with correlation (ADR-0077/0087), DomainMetrics,
scale-to-zero workload tiers with a FinOps classifier (ADR-0057), requests-weighted
cost showback (ADR-0062), LLM budgets + kill switch (ADR-0031 D7), Langfuse for LLM
spans, and DORA metrics (ADR-0061). What credit lacks is the **contract**: which
numbers, at which points, with which budgets — so "fast, cheap, scalable" is checked
by CI and Grafana, not asserted in a pitch.

## Decision

**D1 — Named SLOs (Pyrra, burn-rate alerted).** Initial targets, tunable per
environment but never silently removed:

| Surface | SLO |
|---|---|
| Policy evaluation (ADR-0213) | p99 < 200 ms, in-process, zero DB/IO per evaluation |
| Origination transition command (ADR-0211) | p99 < 500 ms |
| Disbursement ledger posting (ADR-0028 D3) | p99 < 2 s (bounded by `LedgerCallGuard`) |
| Evidence bundle query (ADR-0214) | p99 < 3 s |
| Officer copilot artefact (ADR-0217) | p95 < 10 s (LLM path, budget-capped) |
| Origination funnel | time-in-state per state, application→disbursed conversion, four-eyes SLA adherence — as DomainMetrics, reviewed weekly |

**D2 — Measured at every point.** One OpenTelemetry span per origination state
transition, policy evaluation, ledger posting, Temporal activity and agent call —
correlated end-to-end by `loanApplicationId` (ADR-0087). LLM spans carry token/cost
attributes via Langfuse (ADR-0031 D7). If a code path moves money or makes a decision
and emits no span, that is a review finding, not a preference.

**D3 — Scalability shape.**
- The lending service is stateless behind HPA (CPU + Kafka lag); its Postgres is
  per-service (ADR-0009) with the accruable-scan partial index pattern already proven
  in the accrual loop (ADR-0028 Phase 2).
- **Policy tables and compliance packs are compiled into immutable in-memory
  structures at activation** and version-pinned per request — evaluation is O(rules)
  over memory, never a DB round-trip; a new version swaps an atomic reference, not a
  cache flush.
- Temporal workers scale independently of the API pods; durable timers add no load
  while waiting (the ADR-0101 argument — waiting is free, polling is not).
- Workload tier per the ADR-0057 classifier; money-path availability requirements
  apply (no scale-to-zero for the API/worker plane if the classifier assigns T0/T1).

**D4 — FinOps guardrails.**
- **Unit economics**: cost per originated loan and per serviced loan-month, derived
  from ADR-0062 showback (requests-weighted) + Langfuse LLM cost — reported in the
  governance dashboard next to coverage and DORA, so unit cost rots as visibly as
  code coverage.
- **LLM budget caps per agent per day** with the ADR-0031 kill switch; the model
  gateway routes sensitive/classification work to self-hosted vLLM first and external
  models only per charter (cost *and* data-residency control).
- **Efficiency invariants**: policy evaluation allocates no per-request DB
  connections; accrual/servicing sweeps stay single-query paged (the existing partial
  index); CI cost stays path-scoped (ADR-0040) — credit builds do not tax the fleet.

**D5 — Performance is a gate, not a vibe.** The decision-engine evaluator ships with
JMH-style microbenchmarks in CI (p99 budget asserted), and the origination funnel
SLOs are Pyrra objects — an SLO regression blocks promotion the way a coverage
regression blocks a merge (ADR-0020 pattern). Before any production go-live, the
origination funnel passes a load test at the planned peak day volume with 10× headroom
(the load tier of the ADR-0011 testing pyramid), executed against the deterministic
simulation harness (ADR-0100/0115) so money-path invariants hold under load, not just
at rest.

## Alternatives considered

- **"Measure later, ship first."** The standard path to un-debuggable latency and a
  surprise NAT/LLM bill — the repo's own FinOps history (ADR-0058, runner tool-cache)
  is the counter-argument. Rejected.
- **Per-request policy/pack loading from Postgres.** Simpler code, and it puts the
  hottest path of origination on the database — a latency and cost tax on every
  decision forever. Rejected (D3 compile-at-activation).
- **Scale-to-zero for all credit components.** Cheapest idle cost, but money-path
  availability and the disbursement SLO conflict; the ADR-0057 classifier already
  encodes that trade-off per tier. Deferred to the classifier, not hand-waved here.
- **Central platform-wide performance ADR instead of credit-scoped.** Credit has the
  sharpest contracts (a pure evaluator with a hard p99; an LLM path with a budget);
  other domains set their own numbers. Rejected — scoped contracts, shared tooling.

## Consequences

**Positive**
- "Brutally fast, measurably cheap" becomes falsifiable: SLO objects, unit-cost
  panels and CI benchmarks either pass or page.
- Compile-at-activation policy evaluation makes the deterministic floor (ADR-0213)
  cheaper per decision than any DB-backed rules engine — performance *and* lawfulness
  from the same design.
- LLM cost — the only open-ended spend in the credit stack — is capped, attributed
  and kill-switched by construction.

**Negative**
- SLO ownership is real on-call work: burn-rate alerts on a money-path service page
  humans (GoAlert, ADR-0088).
- Microbenchmark gates add CI time to a service that already carries the heaviest
  governance gates.

**Neutral**
- Initial SLO numbers are targets to calibrate against sandbox telemetry, recorded in
  Pyrra — changing them is a reviewed PR, not a dashboard edit.
- No new infrastructure: Pyrra, OTel, Langfuse, showback all exist.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    SLOs + burn-rate alerting + durable timers are the operational-resilience
           evidence (Art. 11/17 with ADR-0211/0214); capacity and cost telemetry feed
           ICT-risk reporting.
- GDPR:    not applicable (telemetry carries ids and timings, not PII).
- PSD2:    not applicable.
- CNB:     measurable service quality on a money-path product; unit-cost and funnel
           metrics support supervisory operational reviews.

## References

- ADR-0088 — SLO-as-code (Pyrra) + on-call; ADR-0077/0087 — observability stack
- ADR-0062 — FinOps showback (unit economics); ADR-0057 — workload tiers/classifier
- ADR-0031 D7 — LLM budgets, Langfuse, kill switch; D6 — model gateway routing
- ADR-0058 / runner tool-cache (repo FinOps history — the cost of unmeasured spend)
- ADR-0028 — lending cash path + accrual index pattern; ADR-0009 — DB per service
- ADR-0101 — Temporal (waiting is free); ADR-0211 — origination states
- ADR-0213 — the pure evaluator whose p99 is gated here
- ADR-0217 — the LLM surfaces whose budgets are set here
- ADR-0020 — ratchet pattern (SLO regression as a gate); ADR-0040 — path-scoped CI
- ADR-0061 — DORA metrics from in-house sources
