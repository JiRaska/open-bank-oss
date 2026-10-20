---
date: 2026-10-20
decision-status: proposed
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, governance, audit]
summary: "Fraud rule money thresholds (per rule, per currency) become versioned, effective-dated sets activated four-eyes at runtime and read per score; rule logic stays code; a missing set or currency fails closed to REVIEW."
---

# ADR-0347 — Fraud rule thresholds as versioned effective-dated four-eyes data

## Context

ADR-0084 §3 decided that fraud rules are "code-reviewed config — not a UI-editable rule
engine", and its Alternatives section rejected a UI-editable engine because runtime-mutable
money-path behaviour should be limited to break-glass and non-engineering policy (ADR-0047's
scope; ADR-0084 cites it as ADR-0033, a numbering slip). That decision was about rule LOGIC. It
also froze the money figures the v3/v4 amount rules compare against as Kotlin constants in
`FraudRuleEngine.kt`: six numbers (CZK/EUR for `LargeSingleTransactionReviewRule`,
`VelocityH1HighValueReviewRule`, `NewPayeeHighAmountReviewRule`), each commented "first-pass
calibration only — not risk-team-approved figures" (#13437, found by the 2026-10-20 reference-data
audit).

That shape is wrong for the figures, and ADR-0084 does not cover them:

- They are business reference data owned by the risk function. ADR-0047 already names "AML/
  sanctions thresholds" as business-policy parameters that need segregation of duties,
  effective-dating and an immutable trail, and puts them inside the runtime control plane.
- A risk officer cannot recalibrate without an engineering release, and the PR reviewers who do
  approve a change are engineers, not the risk function.
- Nothing records which figures a past verdict was evaluated against except git history of a
  source file, which a supervisor cannot be expected to read.

The platform has made this decision before for other domains. ADR-0138 (fees) and ADR-0212
(credit compliance packs) moved reference data into versioned, effective-dated, fail-closed data
with a closed vocabulary; ADR-0212 D4 activates each version four-eyes at runtime through the
shared `Proposal`/`ProposalState` governance types. This ADR applies that pattern to fraud
thresholds and narrows ADR-0084 §3 to rule logic. It does not supersede ADR-0084.

## Decision

**D1. Thresholds are data, logic is code.** A *threshold set* is a versioned record mapping each
amount rule (a closed set of keys: `large-single-transaction`, `velocity-h1-amount`,
`new-payee-high-amount`) to a map of ISO 4217 currency to a positive amount. Every rule key must
be present and no other is accepted. Which rules exist, what they compare and what they score
stays in `FraudRuleEngine.kt` and changes only by a money-path PR (ADR-0084 §3 unchanged for
logic). The velocity *count* caps are not money figures and stay code; moving them is a separate
decision.

**D2. Four-eyes activation at runtime.** A maker (`ROLE_RISK`/`ROLE_COMPLIANCE`) proposes a
complete version over `POST /api/v1/fraud/thresholds/proposals`; a different principal decides it
over `…/proposals/{id}/decide`. maker ≠ checker is enforced three times: `Proposal.approve`
(422), a `CHECK (decided_by <> proposed_by)` constraint, and OPA, which admits only
risk/compliance humans and vetoes every machine principal and the blanket operator grant. The
decision is a conditional UPDATE on `state = PROPOSED`, so concurrent decisions cannot both win.
Neither POST takes an `Idempotency-Key`, and both are recorded exceptions in the idempotency
coverage baseline: a propose retry with identical content, version and `effectiveFrom` replays
the pending proposal (and the live version number is unique), and a repeated decide is refused by
that same state guard.

**D3. Effective-dated, never back-dated.** A proposal may name a future `effectiveFrom`. Approval
fixes `active_from` = the later of `effectiveFrom` and the approval instant. The set in force at
time *t* is the HIGHEST EXECUTED version with `active_from` ≤ *t*. Ordering by version rather
than by date means a higher version always supersedes a lower one: a lower version scheduled for
a later date can never come back into force over a higher one. Withdrawing a scheduled set is done
by approving a higher version; a separate four-eyes cancel verb is not part of this decision.

**D4. Read per score, from the table.** The scoring path reads the active set on each request
(one indexed query, next to the velocity and payee reads it already makes). There is no
in-memory registry, so an approval is in force on every replica at the same instant. That avoids
the cross-pod divergence lending's pack registry needed a refresher for (#3467). Each
`fraud_scores` row records `threshold_set_version`, so any past verdict can be reconstructed from
`rule_version` and that version.

**D5. Fail closed to REVIEW, not DECLINE.** A currency missing from a rule's map makes that rule
return REVIEW for it, as the constants did. No active set at all makes every amount rule return
REVIEW and adds the reason `threshold-set-unavailable`. We chose REVIEW because DECLINE would
refuse every customer payment on a configuration gap, a self-inflicted outage, and because
ADR-0084's rules never produce DECLINE. REVIEW puts a human on what the engine could not
evaluate, which is the convention the unmapped-currency path already established. A database
error on the read propagates like the velocity read does today. The surfaces' own fail-open
rollout flag (ADR-0084 §4) governs that case, not this ADR.

**D6. Bootstrap is the code being replaced.** `V9__fraud_threshold_set.sql` creates the schema
and seeds version 1 with exactly the six constants, recorded as decided by the money-path PR
review those constants already passed. This departs from ADR-0212 D4 ("content never rides a
migration") deliberately. The seed is not new content but the existing approved behaviour, moved
with the code that stops reading it, so verdicts are byte-identical on rollout and no packless
window exists. Every later version is a runtime activation.

**D7. No new event.** fraud-service emits no rule-change event. The table row (maker, checker,
times, reason, content hash) is the audit record. A consumer that needs change notifications is a
new decision.

## Alternatives considered

- **Keep the constants; change them by PR (ADR-0084 status quo).** PR review gives four-eyes and
  git gives history. Rejected: the approvers are engineers rather than the risk owner,
  recalibration needs a release, effective-dating is merge time, and no verdict row says which
  figures applied.
- **Externalise to `application.yaml` / GitOps config.** No release, still reviewed. Rejected: it
  is the same PR-review control with a different file, gives no effective date or per-verdict
  version pin, and is applied per pod at restart, so replicas disagree during a rollout.
- **A general runtime rule engine (rules as data).** Rejected again for the ADR-0084 reasons:
  logic changes deserve code review, tests and a release. Only the figures moved.
- **In-memory registry with a refresher (ADR-0212's shape).** Saves one indexed read per score.
  Rejected: it brings back the cross-replica divergence window that #3467 had to bound, on a control
  where pods must not disagree.

## Consequences

**Positive**
- The risk function owns and approves its figures; a recalibration is a four-eyes data change
  with an effective date, not a release.
- Every verdict is reconstructable: `rule_version` + `threshold_set_version`.
- One pattern across fees, credit packs and fraud thresholds (ADR-0138/0212).

**Negative**
- One more database read on the scoring hot path (an indexed single-row query).
- A runtime write path to a fraud control exists that did not before, protected by authz and
  four-eyes rather than by its absence. The threat model is updated for it.
- `rules.yaml`'s fraud-service four-eyes note ("no rule-config endpoint exists") is no longer
  true. It is left for the next change that touches the OPA read set, because editing that
  comment alone restamps all 53 OPA bundles and rolls every pod. The policy itself is correct
  now: maker-checker lives in the service, the lending compliance-pack stance.

**Neutral**
- Today scoring is shadow-only on every rail (ADR-0084 correction block), so no payment outcome
  changes either way. The control matters from the challenge/enforce phases on.
- Enforcement of "no money constants in rule code" is the `enum-reference-data-ratchet` gate
  (#13443). This change removes the fraud cluster from its baseline once that gate is on `main`.

### Delivery check

```bash
# no money literal left in the rules
git grep -nE 'BigDecimal\("[0-9]' -- openbank-fraud-service/src/main/kotlin/com/openbank/fraud/domain/rules/
# expected: no output

# the engine cannot be called without a threshold set
git grep -n 'fun score(request: ScoreRequest, thresholds: FraudThresholdSet?)' -- openbank-fraud-service/src/main
# expected: one hit in FraudRuleEngine.kt

# the four-eyes constraint exists in the schema
git grep -n 'ck_fraud_threshold_set_four_eyes' -- openbank-fraud-service/src/main/resources/db/migration
# expected: one hit
```

`FraudThresholdActivationIT` drives the whole sequence (seed parity, proposal inert, self-approval
refused, approval effective, fail-closed) over HTTP against a real Postgres.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA:    a change to a fraud control's parameters is a governed change with segregation of
           duties and an immutable trail (the change-management concern ADR-0047 cites).
- GDPR:    not applicable — threshold sets carry no personal data; maker/checker are staff
           principal names already present in audit trails.
- PSD2:    supports the transaction-monitoring evidence ADR-0084 builds toward, since each
           verdict records the exact figures it was evaluated against.
- CNB:     not applicable — no supervisory return or ČNB-specific duty changes.

## References

- ADR-0084 §3 (rule logic stays code-reviewed; narrowed here to logic)
- ADR-0047 (business-policy parameters belong to a governed runtime path)
- ADR-0138, ADR-0212 D4 (versioned effective-dated data, four-eyes activation)
- ADR-0002 (pure domain: the engine takes the set as an argument)
- Issue #13437; ratchet gate PR #13443
