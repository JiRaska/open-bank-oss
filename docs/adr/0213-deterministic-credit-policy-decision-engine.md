---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, compliance, architecture]
summary: "Credit policy is versioned decision tables evaluated by a pure, in-memory, fail-closed engine with machine-readable reason codes; ML (ADR-0142) may only be stricter inside this deterministic floor."
---

# ADR-0213 — Deterministic credit policy decision engine: versioned decision tables, fail-closed, explainable

## Context

ADR-0142 (credit decisioning on the ML platform) mandates that "the policy floor is
deterministic and supreme": affordability and hard eligibility are **deterministic
rules, not learned**, and the ML model may only be *stricter* inside the policy-allowed
set. What ADR-0142 deliberately does not specify is the **mechanism** of that
deterministic layer. Today it does not exist: eligibility, DTI/DSTI affordability,
exclusion rules and pricing bands would otherwise be hand-coded per product — the same
"free-text pathology" ADR-0138 found for fee waivers, with the same result: a policy a
human can read but the bank cannot execute or evidence.

The requirement set is sharp and regulatory, not technological:

- **Explainable by construction** — every outcome carries machine-readable reason
  codes that become the adverse-action principal reasons (ADR-0142's "no reasons ⇒ no
  decision" contract; EBA/GL/2020/06; CZ consumer-credit law).
- **Versioned and effective-dated** — a decision must be reproducible later against
  the exact policy that produced it (ADR-0214 evidence).
- **Fail-closed** — an unevaluable input or unknown attribute never yields an approval;
  it yields `REFER` (human review), never a silent pass.
- **Business-authorable** — credit-risk analysts change policy by configuration review,
  not by shipping service code (the ADR-0138 "product engine" argument).

Kogito/DMN was evaluated as the vehicle and rejected (ADR-0211): a DMN runtime is a
platform to operate, and the demonstrated policy vocabulary — comparisons, thresholds,
set membership, table lookups — fits in a few hundred lines of pure Kotlin.

## Decision

**D1 — Decision tables as versioned data + a pure evaluator in `openbank-libs`.**
Credit policy is a set of **decision tables** (`PolicyTable`: eligibility, affordability,
exclusion, pricing-band), each a versioned, effective-dated list of rules over a closed
attribute vocabulary (`PolicyAttribute`: verified income, existing debt service, DTI,
DSTI, age, residency, employment tenure, product, jurisdiction, bureau score band, …)
and closed operators (the ADR-0138 comparison set + `IN` / `NOT_IN`). The evaluator is
a pure function in `openbank-libs` (`libs/decision`), zero framework imports
(ADR-0002):

```
evaluate(application, PolicyBundle) → PolicyDecision
PolicyDecision = APPROVE(priceBand) | REFER(reasons) | DECLINE(reasons)
               + matched rule ids + policy version + input attribute snapshot hash
```

**D2 — Fail-closed to REFER, never to APPROVE.** Missing attribute, unevaluable
condition, expired/absent policy table, or a jurisdiction-mandated check (from the
ADR-0212 pack) with no data ⇒ `REFER` with an explicit reason. The only path to
`APPROVE` is every hard rule evaluated and passed. DECLINE rules are first-match;
eligibility is all-pass; pricing bands are last-stage and never flip a decline.
**Outcome wiring into the origination graph (ADR-0211):** `REFER` and `APPROVE` both
route the application to `FOUR_EYES` — a *referred* application for mandatory human
judgement, an *approved* one for the checker's confirmation (credit approval is
four-eyes per ADR-0028 D5 regardless); `DECLINE` moves it to `DECLINED` with the
mandatory human-intervention path for adverse outcomes (ADR-0142).

**D3 — The ML model operates strictly inside the floor** (ADR-0142): the engine's
output is the *policy decision*; ML re-prices or declines within it, and the DST subset
invariant (ML-approved ⊆ policy-approved, ADR-0100) is checked against this engine's
output. Phase 1 ships the engine **without** ML — deterministic-only decisions are
already lawful and useful; ADR-0142's phases layer on top. Pricing output is expressed
as the harmonised APRC (the single CCD2 Annex I formula in libs), disclosed under the
pack's label and locale (ADR-0212), so
the disclosed rate and the decision rate are the same number computed the same way;
bureau and external scoring inputs arrive only via the ADR-0028 D4 port (no-op default
⇒ fail-closed to REFER per D2).

**D4 — Versioned, four-eyes-governed, evidence-bound.** Tables are authored as reviewed
data (same governance as ADR-0212 packs: PR review + four-eyes activation +
`effectiveFrom`), pinned on the application at decision time, and every evaluation
emits the ADR-0214 evidence event (inputs snapshot hash, table version, matched rules,
outcome). Re-evaluating a historic application against its pinned version is a pure
function — reproducibility is a test, not a hope.

**D5 — Boundary with OPA.** OPA remains the *authorization* PDP (who may call what,
ADR-0034). The decision engine is the *credit policy* brain (what the bank decides).
Credit policy does not move into Rego: business analysts author tables with reason
codes, not policy-as-code in an authz language.

## Alternatives considered

- **DMN engine (Kogito/Drools).** Standard notation, but a heavy runtime, a second
  persistence/ops surface, and explanation output that still needs mapping to our
  adverse-action contract. Rejected (with ADR-0211) — the vocabulary fits a pure
  function.
- **Hard-coded Kotlin policy per product.** Status quo trajectory; every policy change
  is a service release and historic decisions are not reproducible against versioned
  policy. Rejected (the ADR-0138 argument).
- **OPA/Rego as the credit-policy engine.** Already deployed and policy-gated, but it
  is an *authz* PDP: table authoring, reason-code contracts and effective-dating are
  not its shape, and mixing credit policy into the authz layer blurs a boundary
  supervisors probe. Rejected as the decision surface; unchanged as authz.
- **ML-first decisioning.** Rejected by ADR-0142 already: the deterministic floor is
  supreme; ML is optional upside inside it.
- **Decision tables in a general expression DSL (MVEL/CEL).** Rejected per ADR-0138 —
  scripting inside a money-path decision is a security surface; closed vocabulary first.

## Consequences

**Positive**
- "Schváleno rozhodovacím enginem" becomes a small, pure, fully tested function — with
  regulator-grade explanation (reason codes + matched rules + versions) as the output
  contract, not a log line.
- Policy changes are data PRs with four-eyes activation — credit risk iterates without
  touching the money path's code.
- Reproducible decisions: pinned versions + pure evaluation = deterministic replay
  under DST (ADR-0100).
- The ADR-0142 ML platform gets a well-defined supreme floor to be constrained by.

**Negative**
- Another closed vocabulary to outgrow (same bet as ADR-0138/0212); the attribute set
  must be designed against the real CZ consumer-credit policy up front.
- Reason-code quality is now a *product* surface (customers read adverse-action
  reasons) — needs a curated, localisable code catalogue, not engineer strings.

**Neutral**
- Phase 1 is deterministic-only; no ML dependency, no feature-store dependency.
- Tables are reference data governed like ADR-0212 packs; no new infrastructure.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    decision logic as versioned, reproducible data lowers change risk on a
           money-path asset; evaluations are evidence (ADR-0214).
- GDPR:    Art. 22 — DECLINE/REFER carry the principal reasons; borderline and adverse
           outcomes route to four-eyes (ADR-0142 HITL). Input snapshots are minimised
           (hash + pointers) in the evidence trail.
- PSD2:    not applicable.
- CNB:     EBA/GL/2020/06 creditworthiness assessment made executable and evidenced;
           affordability is a hard deterministic floor, never learned.

## References

- ADR-0142 — credit decisioning engine on the ML platform (the supreme-floor mandate
  this ADR implements; adverse-action contract; HITL)
- ADR-0028 D4 — bureau/risk inputs via no-op-default ports (fail-closed inputs)
- ADR-0138 — configuration-driven rule engine (the pattern: closed vocabulary,
  fail-closed, pure evaluator)
- ADR-0212 — jurisdictional compliance packs (mandatory checks as engine inputs)
- ADR-0214 — audit evidence (evaluation records)
- ADR-0100 — deterministic simulation testing (subset invariant, replay)
- ADR-0034 — OPA authz (the boundary D5)
- ADR-0139/0140/0141 — ML decisioning platform, feature store, model registry
  (phase-2+ consumers of the floor)
- EBA/GL/2020/06; zákon č. 257/2016 Sb.
