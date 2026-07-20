---
date: 2026-06-29
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fees-billing, product-catalog, architecture]
summary: "Fee waivers move to a configuration-driven rule engine: a closed whitelisted predicate model with a best-effort parser for the legacy free-text grammar and a pure evaluator that fails closed and charges the fee when a rule is unparseable."
---

# ADR-0138 — Configuration-driven product fee rule engine

**Delivery note (updated 2026-07-17):**
- **Phase 1a (parser/evaluator)** — ✅ Shipped: structured whitelisted predicate model
  (`WaiveConditionParser`/`WaiverEvaluator`) with tests.
- **Phase 1b (promote to `openbank-libs`; surface the rule on `/api/v1/fees`)** — ✅ Shipped: the
  predicate model and evaluator are in `openbank-libs-domain`
  (`com.openbank.libs.product.WaiveCondition` / `WaiverEvaluator`; ADR-0122 later moved
  `openbank-libs` into the domain/runtime split). `product-catalog`'s `FeeRuleEvaluator` is now a
  fee-typed adapter over the shared evaluator rather than a second copy, and `FeeScheduleItem`
  carries `waiverEvaluable` + `waiverRule`, so `/api/v1/fees` exposes both the parsed rule and
  whether it is machine-evaluable.
- **Phase 2 (runtime fee posting)** — ✅ Shipped, under its own **ADR-0143** (runtime product fee
  posting via a dedicated billing service): `openbank-billing-service` assesses fees per cycle and
  books them through the ledger + outbox, four-eyes gated on `billing.post`, with the
  `billing-fee-conservation` DST invariant and a threat model
  (`docs/threat-models/openbank-billing-service.md`). ADR-0143 carries that work's delivery status —
  real-environment e2e verification and the four-eyes enforcement flip are tracked there, not here.
- **Phase 3 (interest + eligibility)** — ⬜ Pending, and the only phase of this ADR still open.
  `Product.bonusRateCondition` remains free text (seeded `"No withdrawals in calendar month"`). It
  does **not** fit the phase-1 grammar `<attribute-phrase> <op> <number> [currency]` — it is a
  temporal/event predicate, not a comparison — so phase 3 has to extend the vocabulary, not merely
  reuse the evaluator as this ADR's "Deferred" section assumed.

## Context

OpenBank has a rich product **catalog** (`openbank-product-catalog`) — the
system-of-record for product master data, including a per-product `fees` list
(`Fee(type, frequency, amount, currency, waivable, waiveCondition)`). It does
**not** have a product **engine**: the catalog stores fee data, but nothing
*executes* it. Fee economics are either not applied at runtime at all, or are
hand-coded per consuming service. This is the line that separates a catalog
from the product engines that competing core-banking platforms (Mambu's product
builder, Thought Machine's smart contracts) are sold on — a bank changes product
economics by *configuration*, not by shipping code.

The sharpest symptom is `Fee.waiveCondition`. Today it is a free-text `String?`.
The seeded values are not machine-evaluable and are not even in one language:

| Product fee | `waiveCondition` (free text) |
|---|---|
| Personal current | `Balance > 50 000 EUR` |
| Business current | `Monthly turnover > 1 500 EUR` |
| CZK current | `Měsíční obrat > 25 000 CZK` |
| Multi-currency umbrella | `Souhrnný zůstatek kapes > 20 000 EUR` |

A waiver rule that can only be read by a human cannot be enforced by the bank.
There is no runtime that decides "is this monthly fee waived for this account
this cycle?" from the product definition — so the waiver is, in effect, marketing
copy. **Why now:** this is the highest-leverage, lowest-blast-radius first cut at
a real product engine. Fees have the simplest economic model already present in
the domain, and the waiver decision is read-only (no value movement), so we can
establish the "config → executable rule → runtime evaluation" pattern without
touching the money path. Interest accrual and eligibility — the same free-text
pathology (`SavingsConfig.bonusRateCondition`, `eligibilitySegments` are static
metadata that nobody enforces) — reuse the pattern in later phases.

## Decision

We will introduce a **configuration-driven fee rule engine**, starting with the
fee-waiver decision, delivered in phases. The product definition stays the single
source of truth; the engine is a pure, stateless evaluator that turns a fee's
declared condition into an executable decision.

**Executable rule model (this PR — phase 1a).** We add a structured, whitelisted
predicate model in the catalog's domain layer (`com.openbank.productcatalog.domain`):

- `WaiveAttribute` — a closed enum of evaluable account attributes
  (`BALANCE`, `MONTHLY_TURNOVER`, `AGGREGATE_POCKET_BALANCE`, `SEGMENT`, `CURRENCY`).
- `WaiveOperator` — a closed set of comparisons (`> >= < <= == !=`).
- `WaivePredicate` — a sealed type: `Comparison(attribute, operator, threshold,
  currency)` or `Unparseable(raw, reason)`.
- `WaiveConditionParser` — a **best-effort migration parser** for the existing
  free-text grammar `<attribute-phrase> <op> <number> [currency]`, with an
  EN+CS synonym whitelist. It is deliberately *not* a general expression language:
  no scripting, no `eval`, a fixed attribute/operator vocabulary. Anything outside
  the grammar returns `Unparseable` — it is never coerced into a guessed rule.
- `FeeRuleEvaluator` — a pure function `assess(fee, FeeContext) → FeeAssessment`
  that decides whether a fee is waived and what the effective charge is.

**Fail-closed.** If a condition is unparseable, or the required `FeeContext`
attribute is unknown, or a currency mismatch cannot be resolved (no FX in this
phase), the fee is **not** waived (it is charged) and the assessment carries an
explicit machine-readable `reason`. The bank never silently waives a fee on a
condition it could not actually evaluate.

**Wiring (this PR).** The evaluator is exercised by the catalog at product
`create`/`update`: each waivable fee's condition is parsed and a structured
warning is logged for any condition that is not machine-evaluable, making the
"free text vs. executable" gap *visible* on real data; a fee flagged `waivable`
with a **blank** condition is a data error and is rejected. The in-memory seed is
constructed outside `create()`, so it is unaffected; the four seeded conditions
above are covered by the grammar and proven to parse in tests.

**Deferred (named, not hidden).**
- *Phase 1b* — promote `FeeRuleEvaluator` to `openbank-libs` for cross-service
  reuse and surface the parsed rule + evaluability on the `/api/v1/fees`
  response (an additive, backward-compatible API change).
- *Phase 2* — **runtime fee posting**: a money-path consumer assesses fees per
  cycle/transaction and books them through the existing ledger + outbox path,
  idempotently. This is where the engine touches value movement and inherits the
  money-path controls (2 approvals + threat model, DST conservation invariant).
- *Phase 3* — reuse the pattern for interest (`bonusRateCondition`) and for
  enforced eligibility at account-open.

## Alternatives considered

- **General expression language / rule DSL (MVEL, CEL, scripting).** Maximally
  flexible, but a large new security surface (sandbox escapes, DoS via crafted
  expressions) inside a money-path service, and overkill for the demonstrated
  conditions. Rejected for phase 1 in favour of a closed attribute/operator
  whitelist; a richer expression layer can be reconsidered if real conditions
  outgrow the grammar.
- **Keep `waiveCondition` free-text, interpret it in each consumer.** Status quo.
  Every consumer re-implements parsing, drifts, and the bilingual free text is
  unparseable anyway. Rejected — this is the problem.
- **Robust natural-language parsing of the existing strings.** Fragile and
  open-ended (two languages already, more later); encodes the wrong contract.
  Rejected — the structured model is the contract going forward; the parser is a
  bounded migration aid, and unrecognised text fails closed rather than guessing.
- **Put the evaluator in `openbank-libs` now.** Correct end-state for reuse, but
  triggers a full-fleet rebuild and libs merge friction for code with one
  consumer today. Deferred to phase 1b; the evaluator is pure domain with zero
  framework imports, so the move is mechanical.

## Consequences

**Positive**
- First executable slice of a real product engine: a fee-waiver decision is now
  derived from the product definition by configuration, not hand-coded.
- The bilingual free-text conditions become machine-evaluable on real seed data;
  non-evaluable conditions are surfaced instead of silently ignored.
- The pattern (declarative config in the catalog → pure stateless evaluator →
  fail-closed decision) is directly reusable for interest and eligibility.
- Pure domain, zero framework imports (ADR-0002), fully unit-testable.

**Negative**
- A new, narrow grammar that real-world conditions can outgrow; out-of-grammar
  conditions are logged as non-evaluable until the vocabulary is extended.
- A second representation of the waiver (free text + parsed) until a later phase
  makes the structured form authoritative; they can drift if not validated.

**Neutral**
- No money is moved in this phase; no API contract change; no DB schema change
  (the catalog is still the in-memory seed, per its tracked persistence follow-up).
- Money-path posting (phase 2) will require the full money-path gate (2 approvals,
  threat model `docs/threat-models/product-fee-engine.md`, DST fee-conservation
  invariant) — explicitly out of scope here.

## Compliance impact

- PCI DSS: not applicable (no cardholder data; fee metadata only).
- DORA:    not applicable to this phase (no change to operational resilience
           surface); phase 2 posting falls under existing ledger ICT controls.
- GDPR:    not applicable (no personal data; evaluation context is supplied by
           the caller and not persisted by the evaluator).
- PSD2:    not applicable.
- CNB:     neutral — transparent, deterministic fee waiver improves auditability
           of pricing; phase 2 posting must reconcile to the ledger.

## References

- ADR-0002 — hexagonal architecture per service (domain has zero framework imports)
- ADR-0013 / ADR-0014 / ADR-0049 — openbank-libs shared primitives (phase 1b target)
- ADR-0122 — openbank-libs domain/runtime split (where the phase 1b evaluator lives today)
- ADR-0143 — runtime product fee posting via a dedicated billing service (phase 2 was
  delivered under that ADR; its delivery status is tracked there)
- ADR-0033 / ADR-0038 — withholding tax: precedent for policy currently hard-coded
  in a service rather than driven by configuration
- ADR-0048 — API contract version axis (relevant to the phase 1b `/api/v1/fees` change)
- ADR-0100 — deterministic simulation testing (phase 2 fee-conservation invariant)
- ADR-0105 — unified product identity (the catalog is the product source-of-truth)
- ADR-0030 — threat modeling requirement for the phase 2 money-path consumer
