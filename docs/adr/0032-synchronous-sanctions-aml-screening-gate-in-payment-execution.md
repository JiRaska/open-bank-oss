# 32. Synchronous sanctions/AML screening gate in payment execution

Date: 2026-05-30

Status: Accepted

## Context

The multi-currency rollout plan (`docs/strategy/multicurrency-implementation-plan.md`, Phase 4
item #10, finding **G5**) requires AML/sanctions screening to be **wired into payment execution
and FX conversion**. Two screening capabilities already exist as services but nothing calls them:

- **`openbank-sanctions-service`** exposes a *synchronous* screen:
  `POST /api/v1/sanctions/screen` → `SanctionsCheck`, with
  `isHighRisk() = status == HIT || (status == POTENTIAL_HIT && overallScore > 0.85)` and matches
  against `OFAC_SDN | EU_CONSOLIDATED | UN_CONSOLIDATED | HM_TREASURY | FATF_HIGH_RISK | CNB_DOMESTIC`.
- **`openbank-aml-service`** is a *case-management* store: `POST /api/v1/aml/cases` creates an
  `AmlCase` (`screeningType`, `riskLevel`, `OPEN → UNDER_REVIEW | CLEARED | BLOCKED | ESCALATED`),
  idempotent on the `Idempotency-Key` header.

The payment domains already anticipate the outcome but never produce it: `SepaPayment` carries
`SepaRejectReason.SANCTIONS_HIT` and `AML_HOLD`, yet a grep across the repo finds **zero** calls to
`screen`/`sanction`/`aml`/`watchlist`. A payment today flows `RECEIVED → VALIDATED → …` with no
screening interposed — a regulatory hole (AMLD, EU 2015/847 funds-transfer screening, CZ AML Act
253/2008 Sb., and the EU/UN/OFAC asset-freeze regimes that are *strict-liability*).

This ADR settles the **integration mechanics** the plan left open: where the gate sits, what it
decides, and crucially how it behaves when the screening service is unavailable.

## Decision

### A. A synchronous screening gate at the `RECEIVED → VALIDATED` boundary

Screening is the **first processing step** of a payment, performed synchronously inside
`createPayment` *after* the `RECEIVED` row (and its `payment.created` outbox event) is durably
persisted. The caller of `POST /api/v1/sepa-payments` therefore receives an already-screened verdict.
Persisting `RECEIVED` first means a payment is never lost if screening then fails (it can be retried
from a durable state). Both the **debtor** and the **creditor** name are screened (the beneficiary is
the primary asset-freeze target; the debtor catches our own onboarded-but-listed customers).

### B. Three-way decision, encoded as a pure domain policy

`ScreeningPolicy.decide(results)` (framework-free, unit-tested) collapses the per-name results into
one of three outcomes, mirroring the sanctions service's own `isHighRisk` threshold so the two never
drift:

| Outcome | Trigger | Effect on the payment |
|---------|---------|-----------------------|
| **BLOCK** | any `HIT`, any `ESCALATED`, or `POTENTIAL_HIT` with `score > 0.85` | `RECEIVED → REJECTED (SANCTIONS_HIT)` + open a **CRITICAL** AML case |
| **REVIEW** | any `POTENTIAL_HIT` at or below the 0.85 threshold | **stay `RECEIVED`** (held) + open a **HIGH** AML case (`AML_HOLD`) for a human decision |
| **CLEAR** | all names `CLEAR` / `WHITELISTED` (or no names) | `RECEIVED → VALIDATED` |

A `REVIEW` deliberately does **not** auto-reject: a sub-threshold potential hit is a false-positive
candidate that an analyst clears or escalates via the existing AML case lifecycle. Holding it in
`RECEIVED` (rather than inventing a new status) reuses the current state machine — `RECEIVED` already
*is* the pre-validation holding state.

### C. Fail-closed on screening unavailability

If the sanctions service is unreachable (timeout / open circuit / 5xx) the gate **does not release
the payment**. The payment stays `RECEIVED`, a `MEDIUM` AML case with `alertCode = SCREENING_UNAVAILABLE`
is opened for operational follow-up, and the call returns the held payment. Releasing un-screened
funds would breach strict-liability freeze obligations, so "fail-open" is not acceptable for a
money-path gate. The cross-service call is wrapped in the standard fault-tolerance posture
(`@Retry` / `@Timeout` / `@CircuitBreaker`, self-injected so the CDI interceptors fire) used by the
outbox dispatchers and the ČNB adapter (ADR-0046).

### D. Hexagonal placement (ADR-0002)

Two new **out-ports** in the consuming service — `SanctionsScreeningPort` and `AmlCasePort` — keep
the use-case free of HTTP. Their adapters are `@RegisterRestClient` bindings
(`@RegisterProvider(OidcClientRequestReactiveFilter::class)` for service-token propagation, config
keys `sanctions-service` / `aml-service`), exactly like `openbank-ledger-service`'s `FxServiceClient`.
The screening request/response and the AML-case contract are **mirrored as local DTOs** in the
adapter; the domain imports nothing from the other services' packages.

The **AML case `partyId`** is a required UUID, but a payment only carries `debtorAccountId` (the party
is not resolved at the payment boundary). The adapter maps `debtorAccountId` into both `partyId` and
`accountId` and puts the human-readable debtor (`name / IBAN`) in `customerReference`, with
`transactionId = payment.id`. Proper account→party resolution (via `account-service`) is a documented
fast-follow; the gate must never block on it.

### Scope

This increment implements the gate in **`openbank-sepa-payment`** as the reference vertical slice
(its domain already models `SANCTIONS_HIT` / `AML_HOLD`). `openbank-domestic-payment`,
`openbank-sepa-instant`, and the `openbank-fx-service` conversion path (`FxService.convert`, screened
before settlement) replicate the same port + adapter + policy in their own follow-up PRs — the policy
helper and the decision table above are the shared contract they implement.

## Alternatives considered

- **Asynchronous screening (consume `payment.created`, screen, then transition).** Rejected for the
  release decision: it creates a window where an un-screened payment can advance, and couples the
  freeze guarantee to event-delivery latency. Synchronous gate is simpler to reason about and
  fail-closed by construction. (Periodic/batch *re-screening* remains async and is out of scope here.)
- **Fail-open when the sanctions service is down** (let payments through, screen later). Rejected:
  asset-freeze breaches are strict-liability; availability of our infra is not a legal defence.
- **A shared screening client in `openbank-libs`.** Deferred, not rejected: with only one consumer
  today it would be premature abstraction. Once the second money-path service wires the same port,
  promote the adapter + DTOs to libs (the port stays per-service per hexagonal ownership).
- **Screen only the creditor.** Rejected: a sanctioned *debtor* (our customer added to a list after
  onboarding) must also be caught; screening both names is cheap and closes that gap.

## Consequences

**Positive**
- Closes the G5 hole: every SEPA payment is screened against the consolidated lists before it can be
  validated; hits are auto-blocked and routed to the AML case queue with full audit lineage.
- Fail-closed behaviour makes the gate compliant under infrastructure failure, not just happy-path.
- The pure `ScreeningPolicy` is the single source of the block/review threshold, unit-tested and
  reused verbatim by the follow-up surfaces.

**Negative**
- Adds a **synchronous** cross-service dependency on `sanctions-service` to the payment-create path;
  an outage holds new payments (by design). Mitigated by retry/circuit-breaker and the manual AML
  case for operational visibility.
- `createPayment` now has an observable behaviour change: a clean payment returns `VALIDATED`, a hit
  returns `REJECTED`. The `openapi.yaml` description and `info.version` are bumped accordingly.

**Neutral**
- No new payment status; the gate reuses `RECEIVED` (hold) / `VALIDATED` / `REJECTED`.
- `partyId` on the AML case is account-derived until party resolution lands (documented).

## Compliance impact

- **AML / sanctions:** EU sanctions regulations (asset freezes, strict liability), EU 2015/847
  (funds-transfer information & screening), CZ AML Act 253/2008 Sb.; screening against
  OFAC SDN / EU / UN / HM Treasury / FATF / CNB domestic lists before funds movement.
- **DORA:** the screening call is a third-party-style integrity dependency on the payment path; it
  runs behind a fault-tolerant adapter with an explicit fail-closed posture and an operational alert
  (the `SCREENING_UNAVAILABLE` AML case) when the dependency degrades.

## References

- `docs/strategy/multicurrency-implementation-plan.md` — Phase 4 item #10 (G5) this implements
- [ADR-0002](0002-hexagonal-architecture.md) — ports/adapters placement this follows
- [ADR-0003](0003-transactional-outbox-for-kafka.md) — the durable `payment.created` event persisted before screening
- [ADR-0046](0046-daily-fx-revaluation-mechanics-and-cnb-rates.md) — the resilient inter-service adapter posture reused
- `openbank-sanctions-service` `POST /api/v1/sanctions/screen`; `openbank-aml-service` `POST /api/v1/aml/cases`
- EU 2015/847; CZ Act 253/2008 Sb.; OFAC/EU/UN/HM Treasury consolidated lists
