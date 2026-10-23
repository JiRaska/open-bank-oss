---
date: 2026-10-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [aml-sanctions, ml, compliance]
summary: "A typed-decision model estimates whether a POTENTIAL_HIT is a true match and orders the analyst queue by that estimate; it never clears, escalates or whitelists, and every check stays in the queue until a four-eyes review decides it."
---

# ADR-0350 — Sanctions review-queue ordering with a typed-decision model

## Context

[ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md) holds
sub-threshold screening hits for an analyst rather than rejecting them, and `sanctions.clear` is
four-eyes gated (ADR-0155, `rules.yaml: money_path_services` names it the highest-risk action in
the service, because a wrongly cleared true positive is a real sanctions violation). A held check is
a `SanctionsCheck` with `status = POTENTIAL_HIT`, a list of `SanctionsMatch` (list, `MatchType`
`EXACT | FUZZY | PHONETIC | ALIAS`, `matchScore`) and an `overallScore`.

The queue is ordered by arrival. `overallScore` is a string-similarity score, not a probability of a
true match: a common Czech surname scoring 0.9 against a listed person with a different date of birth
and nationality is almost certainly a namesake, while an `ALIAS` match at 0.7 with a matching
nationality may not be. The analyst reconciles those attributes by hand, in arrival order, while the
payment waits.

[ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md)
D4 forbids a typed decision from clearing a sanctions hit. This ADR is needed to record what it *may*
do in this service, because sanctions-service is money-path and the line has to be explicit before
any code reaches it.

## Decision

We will add an ordering signal to the sanctions review queue, and nothing else.

**D1 — One output.** `MatchAssessment { LIKELY_TRUE_MATCH, LIKELY_NAMESAKE }` with its probability,
computed per held check from the screened attributes (`name`, `aliases`, `dateOfBirth`,
`nationality`, `entityType`) and each `SanctionsMatch`.

**D2 — Ordering only.** The queue sorts by `P(LIKELY_TRUE_MATCH)` descending, with `ABSTAIN` treated
as `P = 1.0` (an unassessed check goes to the top, never to the bottom). There is **no AUTO band**:
the threshold policy for this use case is fixed at `autoAcceptAt = 1.0`, and the
`typed-decision-thresholds` check rejects any other value for it. The model's output and probability
are shown to the analyst as context, labelled as a model estimate.

**D3 — What it can never do.** It never changes `SanctionsCheckStatus`, never sets `reviewedBy`,
never calls `sanctions.clear`, never whitelists, and never shortens or skips the four-eyes review. A
check leaves the queue only through the existing reviewed path. This is enforced structurally: the
adapter's result type is consumed by the queue read model only, and the use case that transitions a
check takes no `TypedDecision` parameter.

**D4 — Metric of success is the true positives' wait, not throughput.** The shadow period measures
the queue position of checks that analysts later confirmed as `HIT` or `ESCALATED`, under arrival
order versus model order. The ordering ships only if it moves confirmed hits earlier without moving
any confirmed hit later than its arrival-order position plus a declared tolerance.

**D5 — Residency.** Names, dates of birth and nationalities are ADR-0175 class 1. Outside the sandbox
the adapter must be `self-hosted` or contractually EU-resident.

## Alternatives considered

- **Auto-clear low-probability namesakes.** The largest saving in analyst time. Rejected: clearing is
  the control, it is four-eyes by rule, and ADR-0348 D4 forbids it. A cleared true positive is the one
  failure this service exists to prevent.
- **Order by `overallScore`.** Free and deterministic. Rejected as insufficient: it is a name
  similarity and ignores exactly the attributes (date of birth, nationality, entity type) that
  separate a namesake from a match. Kept as the tiebreaker.
- **Hand-written attribute rules** (e.g. a date-of-birth mismatch demotes). Explainable and cheap.
  Rejected *as the whole answer*: partial dates and transliterated names make the rule set grow
  without a probability to gate on. Kept as the deterministic floor: an `EXACT` match on a listed
  identifier is pinned to the top by rule, regardless of the model.

## Consequences

**Positive**
- Likely true matches reach an analyst first, which shortens the time a sanctioned payment sits held
  without changing who decides it.
- The model's answers, set against the analysts' decisions, give a measured namesake rate per list,
  which is useful for tuning the screening thresholds themselves.

**Negative**
- An ordering bias is a real risk even without an AUTO band: a true match ranked low waits longer.
  D4's tolerance and the `ABSTAIN ⇒ top` rule bound it, and the shadow period is where it is measured.
- sanctions-service is money-path, so this ADR and its delivery PRs need two approvals and an update
  to `docs/threat-models/openbank-sanctions-service.md` (ADR-0030).

**Neutral**
- Gate: `typed-decision-thresholds` (ADR-0348) carries a fixed `1.0` for this use case.

### Delivery check

```bash
git grep -n 'enum class MatchAssessment' -- 'openbank-sanctions-service/src/main/**'   # expect 1
# the transition use case takes no typed decision
git grep -n 'TypedDecision' -- 'openbank-sanctions-service/src/main/**/application/usecase/**' # expect none
```

## Compliance impact

- PCI DSS: not applicable; no cardholder data in a screening check.
- DORA: no new critical dependency; an outage leaves the queue in arrival order (`ABSTAIN ⇒ top`).
- GDPR: screened identity attributes are personal data; D5 binds the adapter to ADR-0175 class 1.
  No automated decision about the data subject is taken, because the analyst decides every check.
- PSD2: not applicable; payment execution timing changes only through how fast the analyst reaches
  the check.
- CNB: the sanctions review process and its four-eyes record are unchanged; the model's estimate is
  recorded as a `DecisionRecord` so a reviewer can see what the analyst was shown.

## References

- [ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md) — the class; D4 forbids clearing
- [ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md) — the held-for-analyst gate
- [ADR-0155](0155-four-eyes-enforcement-for-money-path-actions.md) — four-eyes on `sanctions.clear`
- [ADR-0175](0175-data-residency-and-sovereignty.md) — residency class of the screened attributes
- `openbank-sanctions-service/src/main/kotlin/com/openbank/sanctions/domain/model/SanctionsCheck.kt`
