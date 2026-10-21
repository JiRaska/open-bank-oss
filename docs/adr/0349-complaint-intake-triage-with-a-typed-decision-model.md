---
date: 2026-10-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [disputes, ml, compliance]
summary: "Complaint intake is the first ADR-0348 consumer: a typed-decision model suggests ComplaintCategory and a handling priority, shadow first and then auto-routing above a measured threshold; it never touches the deadline, status or outcome."
---

# ADR-0349 — Complaint intake triage with a typed-decision model

## Context

[ADR-0085](0085-complaints-handling.md) and [ADR-0117](0117-dispute-and-complaint-lifecycle.md)
made complaints a first-class aggregate in `openbank-dispute-service` with a regulatory taxonomy
(`ComplaintCategory { PAYMENT_SERVICE, FEES, ACCOUNT_SERVICE, LENDING, CONDUCT, DATA_PROTECTION,
OTHER }`, `domain/model/ComplaintModels.kt`) and a statutory deadline clock. Neither decides *who
assigns the category*. Today the filer does: `FileComplaintRequest.category` is caller-supplied, so
a complaint arriving by `EMAIL` or `BRANCH` is categorised by whichever operator keys it in, and a
customer filing in the app picks a category themselves, with `OTHER` the path of least resistance.

The category feeds the ČNB complaints register (ADR-0085), so a wrong or lazy `OTHER` is a
reporting-quality defect, not a cosmetic one. Two things that matter for this ADR do **not**
depend on the category: the deadline (`ComplaintService` applies 15 business days to every
complaint and 35 after an interim reply, `STANDARD_DEADLINE_DAYS` / `EXTENDED_DEADLINE_DAYS`) and
the lifecycle (`RECEIVED → RESOLVED → CLOSED`, operator-driven). That makes category assignment a
decision with a closed output, high volume and no money attached, which is the shape
[ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md)
admits.

ADR-0348 is the class decision; this ADR is needed because the first consumer has to fix what the
model may write, what it may never write, and how the AUTO band is earned on this taxonomy.

## Decision

We will add complaint intake triage to `openbank-dispute-service` as the first `TypedDecisionPort`
consumer.

**D1 — Two outputs, both closed.**
- `ComplaintCategory`, the existing domain enum, reused as the output schema. The model cannot
  invent a category the register does not know.
- `HandlingPriority { STANDARD, EXPEDITE }`, new. `EXPEDITE` marks complaints that should not wait
  in the general queue: a claimed unauthorised payment, a stated vulnerability, an arbiter
  escalation (`channel = ARBITER` is `EXPEDITE` by rule, not by model).

The input is the complaint `description`, the `channel` and whether `accountId`, `transactionId` or
`disputeId` are present. It never includes party identity fields. The description itself is
personal data, which binds the adapter under D5.

**D2 — What the model may write, and what it may never write.**

| Field | Model effect |
|---|---|
| `category` | **suggestion** in `HUMAN_REVIEW`; **written** only in `AUTO`, and only when the filer supplied `OTHER` or the intake was operator-keyed (`EMAIL`, `BRANCH`) |
| priority / queue | the routing key of the operator work queue |
| `dueDate`, `status`, `outcome`, `redressGranted`, `rootCauseCode` | **never** |

A customer who chose a specific category keeps it; the model's disagreement is recorded as a
suggestion for the operator, not an overwrite. The deadline clock starts at intake as today, before
the model is called, so a slow or abstaining model cannot delay it.

**D3 — Rollout in three steps, each a config change.**
1. **Shadow.** The model runs on every filed complaint and its answer is recorded as a
   `DecisionRecord` (ADR-0348 D6), invisible to operators. The operator's final category at close is
   the label.
2. **Suggest.** The suggestion is shown in the admin-ui complaint view (`HUMAN_REVIEW` for every
   answer, `autoAcceptAt = 1.0`).
3. **Auto.** `autoAcceptAt` drops below 1.0 only after the calibration eval in D4 passes, through
   the four-eyes threshold change of ADR-0348 D3.

**D4 — The AUTO band's bar.** On at least 500 labelled complaints from the shadow period: expected
calibration error ≤ 0.05, Brier score no worse than a class-prior baseline, and **AUTO-band
precision ≥ 0.95 per category** (not only on aggregate, because `DATA_PROTECTION` and `CONDUCT` are
rare and an aggregate would hide them). The eval lives in the ADR-0148 evals registry and replays
on each model or threshold change. A category that misses the bar stays at `HUMAN_REVIEW` while the
others go to `AUTO`.

**D5 — Residency.** The description is ADR-0175 class 1, so outside the sandbox the adapter must be
`self-hosted` or contractually EU-resident (ADR-0348 D8). In the sandbox the corpus is synthetic,
and that is the only place a hosted non-EU adapter may run this use case.

## Alternatives considered

- **Leave categorisation to the filer and the operator.** No new dependency, fully attributable.
  Rejected as the ceiling: it is how the register fills with `OTHER`. Kept as the `ABSTAIN` path,
  which is exactly today's behaviour.
- **Keyword rules over the description.** Deterministic and explainable. Rejected: Czech
  morphology turns a keyword list into a stemming project, rules have no probability to gate on, and
  the shadow labels would still be needed to tune them. Rules stay where they are decisive, as in the
  `ARBITER ⇒ EXPEDITE` rule above.
- **Let the model also propose the outcome or a reply.** The obvious next step, and a generative
  task. Rejected here: an outcome is the operator's decision under ADR-0085, and a drafted reply is
  an LLM assist task for the case-coordinator charter, not a typed decision.
- **Overwrite a customer-chosen category when the model disagrees confidently.** Better register
  data. Rejected: the customer's own classification of their complaint is evidence, and silently
  replacing it is the kind of change a complainant or the arbiter would rightly question. The
  disagreement is shown, not applied.

## Consequences

**Positive**
- Fewer `OTHER` entries and faster routing of expedite cases, both measurable from the
  `DecisionRecord`s without new instrumentation.
- The shadow period yields a labelled Czech complaint corpus, which is the training set the
  ADR-0174 exit path (an in-process ONNX classifier) needs.

**Negative**
- dispute-service gains an outbound dependency. It is non-critical by construction (abstain means
  today's flow), but it is one more thing in its threat model.
- Until an EU-resident or self-hosted adapter exists, the use case cannot leave the sandbox. That is
  a deliberate consequence of D5, not an oversight.

**Neutral**
- `docs/threat-models/openbank-dispute-service.md` gains a row for the model input (the description
  as a prompt-injection carrier: the output is a closed enum, so the worst case is a wrong category,
  which D2 confines to `OTHER`/operator-keyed complaints).
- Gate: `typed-decision-thresholds` (ADR-0348) covers this use case; no service-specific gate.

### Delivery check

```bash
git grep -n 'TypedDecisionPort' -- 'openbank-dispute-service/src/main/**'      # expect ≥ 1 hit
git grep -n 'enum class HandlingPriority' -- 'openbank-dispute-service/src/main/**'  # expect 1
# the calibration eval for this use case is registered
git grep -ln 'complaint-intake-triage' -- 'openbank-libs/governance/**'        # expect ≥ 1 file
```

## Compliance impact

- PCI DSS: not applicable. Complaint descriptions are free text; if one contains a card number, it
  must be masked before the model call, as for any other outbound use of the description.
- DORA: no new critical dependency; the provider, once contracted, enters the ADR-0174 register as
  non-critical (ADR-0174 amendment of 2026-10-21).
- GDPR: the description is personal data; D5 binds the adapter to ADR-0175 class 1. The AUTO band
  (D3 step 3) is an automated decision about a data subject's complaint record and needs a DPIA
  before it is switched on.
- PSD2: the deadline clock is untouched (D2); the 15/35 business-day mechanics of ADR-0117 apply
  whether or not the model answers.
- CNB: the complaints register's category column becomes better populated; ownership of the
  register and of each complaint outcome stays with operators.

## References

- [ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md) — the class, port and thresholds this consumes
- [ADR-0085](0085-complaints-handling.md), [ADR-0117](0117-dispute-and-complaint-lifecycle.md) — taxonomy, deadline clock, lifecycle
- [ADR-0148](0148-ai-assurance-prompt-registry-evals-gate-and-eu-ai-act-mapping.md) — evals registry for D4
- [ADR-0175](0175-data-residency-and-sovereignty.md) — residency class of the description
- `openbank-dispute-service/src/main/kotlin/com/openbank/dispute/domain/model/ComplaintModels.kt`
- `docs/threat-models/openbank-dispute-service.md`
