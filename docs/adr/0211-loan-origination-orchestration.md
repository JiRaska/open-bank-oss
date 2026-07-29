---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, architecture, resilience]
summary: "Origination is a persisted, property-tested state machine on the LoanApplication aggregate; Temporal supplies only durable timers and escalation. Kogito/Camunda rejected; withdrawal runs post-disbursement per CCD2."
---

# ADR-0211 — Loan origination orchestration: persisted state machine with durable timers, not a BPM suite

## Context

ADR-0028 delivered the lending bounded context with a deliberately minimal origination
flow: `LoanApplication` moves `PROPOSED → APPROVED → DISBURSED` (or `REJECTED`) through a
maker-checker REST cycle. That is sufficient to prove the four-eyes invariant, but it is
**not a lawful consumer-credit origination process**. A real origination is a multi-day,
human-in-the-loop, jurisdiction-dependent process with statutory waits and hard
evidentiary duties:

- intake → KYC/AML verification → document collection → creditworthiness assessment
  (ADR-0213 decision engine; ADR-0142 ML inside it) → four-eyes approval → offer
  generation with mandatory pre-contractual disclosures (SECCI/ESIS, ADR-0212) →
  customer signature under SCA → disbursement. The statutory **withdrawal window
  (cooling-off) runs post-disbursement** — CCD2 gives the customer 14 days from
  contract conclusion to withdraw from a *concluded* contract, so gating disbursement
  on its elapse would stall every loan by two weeks without legal cause; withdrawal
  is an unwind path on the live loan (ADR-0215), not an origination gate. Only
  products where national law grants a *pre-contractual* reflection period (e.g.
  MCD mortgages in some member states) insert a wait before disbursement — that is a
  pack-parameterised state, not the default;
- the process must survive **days of wall-clock time** (document chasing, offer expiry,
  cooling-off expiry) with **durable timers**, operator escalation, and the ability to
  reconstruct every step for a supervisor (DORA Art. 11/17; EBA/GL/2020/06).

We evaluated **Kogito** (the KIE/DMN+BPMN stack) as the process engine. It is rejected
here for the same class of reasons ADR-0045 rejected Axon and ADR-0101 rejected further
hand-rolled saga coordinators: it is a second stateful platform (its own persistence,
its own operational footprint, its own audit surface) that fights the platform's
already-standardised orchestration story, and its DMN decisioning duplicates what
ADR-0213 specifies in a fraction of the code. The platform has, in fact, already decided
this question twice:

- **ADR-0045** — deterministic, persisted state-machine primitives in `openbank-libs`
  (`CaseTransitionEngine`, `SagaTransitionPolicy`) are the house pattern for transition
  correctness;
- **ADR-0101** — Temporal is the durable-execution layer for money-path workflows
  (shipped: payments, FX, statements), with the outbox retained inside activities.

Origination sits exactly at their intersection: transition *correctness* is a
state-machine problem; *time* (waiting days for a signature or a cooling-off expiry) is
a durable-execution problem. This ADR decides how the two compose for credit
origination, so the build cannot drift into a third orchestration model.

## Decision

**The state machine is the law; Temporal is the clock.**

**D1 — The `LoanApplication` aggregate in the lending database remains the single
source of truth.** All transitions are validated by a deterministic, pure
`ApplicationTransitionPolicy` in the service domain layer (the ADR-0045
`CaseTransitionPolicy` pattern, zero framework imports, property-tested). The canonical
lifecycle:

```
DRAFT → SUBMITTED → KYC_PENDING → DOCS_REQUIRED → ASSESSMENT
      → DECISION_PENDING → FOUR_EYES → OFFERED → AWAITING_SIGNATURE
      → SIGNED → [REFLECTION_PERIOD]* → READY_TO_DISBURSE → DISBURSED   (terminal)

*REFLECTION_PERIOD exists only where the pinned pack defines a pre-contractual
 reflection wait (e.g. MCD mortgages in some member states); the default
 consumer-credit flow skips it. Post-disbursement withdrawal (the CCD2 14-day
 right) is NOT a state here — it is an exit path on the live loan (ADR-0215).

any pre-DISBURSED state → WITHDRAWN (customer) | DECLINED (decision/four-eyes)
time-driven             → EXPIRED   (offer / document / KYC validity elapsed)
```

Which optional states and waits are *mandatory* is parameterised by the jurisdiction
compliance pack (ADR-0212), but the transition *graph* is fixed in code and identical
for every jurisdiction.

**D2 — A Temporal workflow per application drives only the durable waits and
escalations** (namespace `openbank-lending`, per ADR-0101). The workflow holds **no
business state of its own**: it signals the aggregate's explicit transition commands
(`submitDocuments`, `approve`, `sign`, `reflectionElapsed`, `expireOffer`, …) and calls
idempotent activities that post through the existing outbox. Durable timers cover:
document-collection SLA with operator reminder, four-eyes SLA escalation, offer expiry,
KYC-validity expiry, and the pack-defined reflection-period wait where applicable.
Crash-recovery, retries and the
per-workflow execution history come from Temporal (the DORA Art. 17 reconstruction
surface); the business state comes from the aggregate (the audit surface, ADR-0214).

**D3 — Every transition is an explicit, role-gated, audited command.** Human actions
(four-eyes approve/decline, operator document review) follow ADR-0116: identity from
`SecurityIdentity`, never the request body; maker ≠ checker enforced by the state
machine; minimum reason length. System transitions (timer expiries, decision-engine
outcomes) carry the machine actor. Every transition emits the ADR-0214 evidence event.

**D4 — Disbursement remains the ADR-0028 D3 path** (synchronous REST journal to
ledger-service behind `LedgerCallGuard`, idempotency by posting reference), reachable
only from `READY_TO_DISBURSE` after signature (and any pack-defined reflection wait)
and four-eyes. The
application closes, the `Loan` opens, and the schedule registers in **one local
transaction** (the ADR-0028 D1 consistency boundary), with the outbox row in the same
commit.

**D5 — Sandbox straight-through mode.** A `lending.origination.auto-approve` config
property (default `false`, restart-required — the ADR-0116 STP pattern, **never true in
production**) lets the sandbox run e2e without an operator.

**D6 — Integration contract (reuse, never re-plumb).** Origination consumes existing
capabilities through their established surfaces; every dependency degrades to a no-op
default so the service still builds and boots offline (the ADR-0045 realization
pattern):

| Need | Consumed surface |
|---|---|
| Applicant identity | `party-service` unified client identity (ADR-0072) |
| KYC/AML case | `kyc-service` (ADR-0116), event-driven and idempotent; `KYC_PENDING` unblocks on the case-approved event, not by polling |
| Sanctions/AML screening | **always-on platform invariant**, independent of any pack: the synchronous screening-gate pattern (ADR-0032) runs for every application; a pack can only *tighten* (never waive) screening |
| Credit bureau / scoring inputs | the ADR-0028 D4 `port/out` with a no-op `@Default` ("no bureau data" → the ADR-0213 engine fails closed to REFER) |
| Customer intake + signature | customer edge (ADR-0065) + SCA (ADR-0021); signature artefact hash into ADR-0214 |
| Disclosures / contract documents | rendered from the pinned pack's templates and stored immutably (ADR-0212 D6) |
| Customer communications | `notification-service`, every send evidenced (ADR-0214) |

**D7 — Legacy state migration (v0.11.5 → the canonical graph).** The shipped
`openbank-lending-service` already persists `LoanApplication` rows in
`PROPOSED / APPROVED / REJECTED / DISBURSED` and `Loan` rows in
`ACTIVE / CLOSED / WRITTEN_OFF`. The cutover is an explicit, one-way Flyway data
migration with a documented mapping — never an implicit enum swap:

| Legacy `ApplicationStatus` | Canonical state |
|---|---|
| `PROPOSED` | `DRAFT` (if never submitted) / `SUBMITTED` — distinguished by the presence of a submission timestamp |
| `APPROVED` | `OFFERED` (approved but not yet disbursed ⇒ offer outstanding) |
| `REJECTED` | `DECLINED` (terminal) |
| `DISBURSED` | `DISBURSED` (terminal) |

`LoanStatus` extends **additively** (`DELINQUENT`, `DEFAULTED`, `TERMINATION_NOTICED`,
`ACCELERATED`, `EARLY_REPAYMENT_REQUESTED`, `SETTLEMENT_QUOTED`, `WITHDRAWN`,
`UNWOUND` — ADR-0215): existing `ACTIVE`/`CLOSED`/`WRITTEN_OFF` rows stay valid with
no rewrite. The migration ships with a rollback note and a post-migration invariant
check (row counts per legacy state == row counts per mapped state), run before the
new transition policy is enforced on writes.

## Alternatives considered

- **Kogito (BPMN + DMN, KIE stack).** Full process *and* decision platform, but a
  second stateful system inside the CDE/audit scope, a BPMN modelling culture alien to
  the codebase, and a DMN engine duplicating ADR-0213. Rejected — the same operability
  and audit-surface rationale as ADR-0045, and it contradicts the two orchestration
  standards the platform already ships.
- **Camunda / Zeebe.** Same class of rejection as Kogito, plus a licence/embedding
  story that fights the Apache-2.0 platform.
- **Pure database state machine, no Temporal** (ADR-0045 only). Correct transitions,
  but durable multi-day timers, escalations and replayable execution history must then
  be hand-built per flow — exactly the fragility (the settlement money-bug) that
  motivated ADR-0101. Rejected as incomplete for *time*; retained for *correctness*.
- **Temporal workflow as the source of truth** (aggregate lives only in workflow
  history). Rejected — business state must live in the service's own Postgres
  (ADR-0009) co-located with its audit evidence; Temporal history is an operational
  record, not the system of record, and supervisors read the aggregate, not a replay.
- **Split origination into its own micro-service.** Rejected — ADR-0028 D1 already
  placed origination inside `openbank-lending-service`; disbursement atomicity
  (close application + open loan + register schedule) is one local transaction.

## Consequences

**Positive**
- No new infrastructure and no third orchestration model: correct transitions from the
  ADR-0045 primitive, durable time from the ADR-0101 platform, both already shipped.
- Jurisdiction-variability is *data* (ADR-0212 pack), not per-country code paths or
  BPMN diagrams; the transition graph stays single and property-testable.
- Supervisor-grade reconstruction: Temporal execution history **plus** the tamper-
  evident audit chain (ADR-0214) over one correlation id.
- Origination becomes a governed, evidenced process without giving up the offline-
  buildable, no-op-default house pattern (Temporal workers gate like every adapter).

**Negative**
- Two runtime participants (aggregate + workflow) must be kept consistent; the mapping
  "workflow signal → explicit transition command" is a contract that must be tested
  (DST, ADR-0100), not assumed.
- Temporal becomes a hard dependency of *originating* a loan (it already is one for
  moving money, ADR-0101); a Temporal outage pauses origination progress, though the
  aggregate remains readable and no state is lost.

**Neutral**
- Money-path service: every phase lands under the money-path gate (2 approvals +
  threat-model update `docs/threat-models/openbank-lending-service.md`, ADR-0030).
- Decision-only ADR; no code changes until accepted.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    Art. 11/17 — durable, replayable execution history (Temporal) + tamper-
           evident audit (ADR-0214) as the incident-reconstruction surface.
- GDPR:    Art. 22 — automated decision points route to four-eyes (with ADR-0142);
           applicant data minimised in workflow payloads (ids, not PII).
- PSD2:    SCA on the signature step (customer authentication for contract conclusion).
- CNB:     EBA/GL/2020/06 creditworthiness + four-eyes; Czech consumer-credit act
           process duties parameterised via ADR-0212.

## References

- ADR-0028 — lending bounded context (D1 one service; D3 disbursement path; D5 four-eyes)
- ADR-0045 — libs state-machine/saga primitives (the transition-correctness layer)
- ADR-0101 — Temporal durable execution for money-path workflows (the time layer)
- ADR-0120 — precedent migration of payment orchestration onto Temporal
- ADR-0116 — four-eyes gate mechanics + sandbox STP flag pattern
- ADR-0212 — jurisdictional compliance packs (parameterises mandatory states/waits)
- ADR-0213 — deterministic credit policy decision engine (the ASSESSMENT step)
- ADR-0214 — credit lifecycle audit evidence (the transition evidence contract)
- ADR-0142 — ML credit decisioning inside the policy floor
- ADR-0009 — postgres-per-service; ADR-0003 — transactional outbox
- ADR-0072 — unified client identity; ADR-0032 — sanctions/AML screening gate;
  ADR-0065 — customer edge; ADR-0021 — SCA (the D6 integration surfaces)
- EBA/GL/2020/06 (loan origination and monitoring); DORA Art. 11/17
