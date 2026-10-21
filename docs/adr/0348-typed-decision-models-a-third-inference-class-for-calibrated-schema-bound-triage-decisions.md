---
date: 2026-10-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ml, ai-agents, architecture, privacy-gdpr]
summary: "Typed-decision models (schema-bound enum output plus a calibrated probability) become a third inference class behind one TypedDecisionPort, for triage and ranking only: a deterministic floor, data-held thresholds, fail-closed abstain."
---

# ADR-0348 — Typed-decision models: a third inference class for calibrated, schema-bound triage decisions

## Context

The platform has two inference classes today, and each was decided deliberately:

- **In-process ONNX models** ([ADR-0139](0139-ml-decisioning-platform.md)) — trained, versioned,
  explainable, run inside the consuming service. Fraud scoring is the only consumer
  (`openbank-fraud-service/.../port/out/MlModelPort.kt`, `OnnxFraudModel`). Every model needs a
  labelled training set, a feature definition (ADR-0140) and a model card (ADR-0141) before it
  decides anything.
- **Generative LLMs through the gateway** ([ADR-0031](0031-ai-agent-governance-and-operations.md),
  [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) §2) — free text out,
  `LlmGatewayPort` in `openbank-libs-domain/.../llm/`. ADR-0139 *rejected* this class as a
  decisioner: "non-deterministic, hard to calibrate, unexplainable to a regulator and a
  prompt-injection surface".

ADR-0139 decided the money-path risk plane; ADR-0031/0089 decided the assist plane. Neither covers a
third shape that has become available: a model that reads **unstructured input** (free text, a
loosely shaped record) and returns **one value from a closed, pre-declared schema together with a
calibrated probability**, in a single pass and at a small fraction of an LLM call's latency and
cost. It sits between the two existing classes: it needs no bespoke training set to be useful,
unlike ONNX, and it cannot return a string to be parsed, unlike an LLM. This ADR is needed because
the platform has several high-volume decisions of exactly that shape that are today either made
by hand or not made at all:

- **Complaint intake** — `openbank-dispute-service` carries a regulatory taxonomy and a statutory
  deadline clock (ADR-0085, ADR-0117), and every new complaint is classified into it by an operator.
- **Sanctions review queue** — sub-threshold hits are held for an analyst
  ([ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md)) in arrival
  order, with no estimate of which hits are likely true matches.
- **Transaction categorisation and copilot intent routing** — free-text remittance information and
  customer utterances mapped onto closed vocabularies (ADR-0282, ADR-0089).

A calibrated probability is what makes these decisions automatable *safely*: it lets the platform
act on the confident cases and send only the uncertain ones to a person. That property is only as
good as its calibration on this bank's own data. The vendor claims are vendor-measured, so this ADR
treats calibration as something to measure here, not something to assume.

## Decision

We will introduce **typed-decision models** as a third inference class, governed as follows.

**D1 — One port, framework-free.** `openbank-libs-domain` gains
`com.openbank.libs.decision.typed.TypedDecisionPort`:

```kotlin
interface TypedDecisionPort {
    suspend fun <O : Enum<O>> decide(request: TypedDecisionRequest<O>): TypedDecision<O>
}

sealed interface TypedDecision<out O> {
    data class Decided<O>(val value: O, val probability: Double, val engine: EngineRef) : TypedDecision<O>
    data class Abstained(val reason: AbstainReason, val engine: EngineRef?) : TypedDecision<Nothing>
}
```

The output type is a Kotlin enum the *caller* declares; the adapter maps the provider's schema
onto it and **rejects any value outside it as `Abstained(SCHEMA_VIOLATION)`**, never coerces it.
No provider name, SDK type or endpoint appears in the domain or application layer; adapters live in
`infrastructure/` of the consuming service (ADR-0002) or in `openbank-libs-runtime`.

**D2 — The output schema is code, versioned like an event.** Each use case declares its output enum
and a `schemaVersion`. Adding a value is MINOR; removing or renaming one is MAJOR and needs a new
version beside the old, the same rule events follow.

**D3 — Thresholds are data, not code.** Each use case has a versioned threshold policy
(`autoAcceptAt`, `reviewBelow`), held in the service's configuration and changed only through
four-eyes (the ADR-0155 approval flow). Three bands:

| Band | Condition | Effect |
|---|---|---|
| `AUTO` | `probability ≥ autoAcceptAt` | the decision is applied |
| `HUMAN_REVIEW` | between | a person decides; the model's value is shown as a suggestion |
| `ABSTAIN` | below `reviewBelow`, or `Abstained` | handled as if no model existed |

A use case starts with `autoAcceptAt = 1.0`, i.e. **no AUTO band**, and earns one only through D7.

**D4 — Deterministic floor, as in ADR-0139 and ADR-0213.** A typed decision may *route, rank or
tighten*. It may never loosen a deterministic rule or stand in for a control a rule or a human owns.
Specifically, a typed decision is never the sole input to: moving money, clearing a sanctions or AML
hit, a credit outcome, or closing a customer case. Those stay with the existing rule engines and
four-eyes gates.

**D5 — Fail closed, and abstain is its own outcome.** Timeout, provider error, schema violation,
missing configuration or a disabled flag all produce `Abstained`, which the caller handles as "no
model". Abstain is never folded into success. That conflation shipped once already
(`PushResult.skipped()` carrying `success = true`), so the outcome is a separate type and a separate
metric label.

**D6 — Every typed decision is a `DecisionRecord`.** It is emitted through the ADR-0322 envelope
with `engine.kind = MODEL`, `engine.id`/`engine.version` from the model card (ADR-0141), and the
probability and band in the record. ADR-0322's closed `DecisionClass` gains `TRIAGE`, with outcome
`AUTO | HUMAN_REVIEW | ABSTAIN`. That is a change to `DecisionRecord.kt` delivered with the first
consumer, not a free-form payload key.

**D7 — Calibration is measured here, before any AUTO band exists.** A use case may set
`autoAcceptAt < 1.0` only after a shadow period on its own labelled data shows, in an eval committed
under the ADR-0148 evals registry: expected calibration error and Brier score under the use case's
declared ceiling, and AUTO-band precision at or above its declared floor. The eval replays on every
model or threshold change and blocks promotion on regression.

**D8 — Residency binds the adapter, not the use case.** The adapter declares a
`sensitivity: hosted | self-hosted` exactly as the existing `model-gateway` entries do, and the
enforced `model-residency-claims` gate (ADR-0175 D3) is extended to cover typed-decision endpoints.
Under ADR-0175 §5 a `hosted` adapter outside the EU may receive **only class-3 data** (synthetic or
non-personal). Complaint text, names on a sanctions hit and remittance information are class 1, so
outside the sandbox those use cases need a `self-hosted` or contractually EU-resident adapter.
Pseudonymised data is still personal data and does not change the class.

**D9 — Provider-neutral in the public repo.** Charters, configuration and docs name the class
("typed-decision model"), never a commercial provider. The provider becomes a third-party register
entry under ADR-0174 only when one is contracted.

## Alternatives considered

- **Use the existing LLM gateway with a JSON schema in the prompt.** No new class: structured-output
  prompting already works through `LlmGatewayPort`. Rejected as the *decisioner*. The output is
  still sampled text that has to be parsed and validated, a "confidence" in the text is not a
  calibrated probability, and it carries every objection ADR-0139 already recorded against
  LLM-as-decisioner. Kept as an **interchangeable adapter** behind D1. If an LLM adapter can meet D7,
  nothing stops it, and the point of the port is that a use case does not depend on which one it is.
- **Train an in-process ONNX classifier per use case (ADR-0139).** Best on residency, latency and
  provenance. Rejected *as the starting point*: each use case first needs a labelled set the bank
  does not yet have (complaint categories are assigned by hand today and not exported as training
  data). Kept as the **exit path** (ADR-0174 §5): the shadow period of D7 produces exactly the
  labelled set an in-process model needs.
- **Keep these decisions manual.** Fully explainable and zero new dependency. Rejected as the
  ceiling, because the volume of the queues it would serve grows with the bank, not the team. Kept
  as the `ABSTAIN` path, which is what the platform does whenever no model answers.
- **Let a typed decision clear low-risk sanctions hits.** The largest saving on the review queue.
  Rejected under D4: clearing a hit is the control itself, and ADR-0032 deliberately keeps it with
  an analyst.

## Consequences

**Positive**
- Triage queues can be ordered and partly automated with a measurable risk: the AUTO band's
  precision is a number on a committed eval, not an impression.
- One port means the provider, an LLM adapter or an in-house ONNX model are swappable per use case,
  which is both the DORA exit position and the AGPL/Apache seam ADR-0174 §4 already relies on.
- The shadow period produces labelled data as a side effect, which makes the ONNX exit cheaper.

**Negative**
- A new third-party and transfer surface. For any class-1 use case it is unusable outside the sandbox
  until a self-hosted or EU-resident adapter exists, and this ADR says so instead of letting a sandbox
  success read as production readiness.
- Calibration drifts silently when the input distribution moves, so D7's eval must replay on fresh
  labels, not only on the frozen set.

**Neutral**
- No runtime change until a consumer ADR adopts the port; the first is complaint intake triage.
- Gate: `model-residency-claims` (enforced) gains the typed-decision endpoints; a new
  `typed-decision-thresholds` check asserts that every declared use case either has
  `autoAcceptAt: 1.0` or a passing calibration eval in the evals registry. Advisory until the first
  consumer ships, with a `target_enforce_date` per ADR-0144.

### Delivery check

```bash
# the port exists, framework-free
git grep -l 'interface TypedDecisionPort' -- 'openbank-libs-domain/src/main/kotlin/**'   # expect 1 file
# DecisionClass carries TRIAGE
git grep -n 'enum class DecisionClass' -- 'openbank-libs-domain/**' | grep -c TRIAGE      # expect 1
# the residency gate covers typed-decision endpoints and the threshold gate exists
grep -nE 'id: (model-residency-claims|typed-decision-thresholds)' .github/gates/gates.yaml # expect 2 lines
```

## Compliance impact

- PCI DSS: not applicable. No use case named here reads cardholder data, and D8 would forbid sending it.
- DORA: a contracted provider is an ICT third party and enters the ADR-0174 register with an exit
  position (the ONNX path above); until one is contracted there is no entry, because no dependency
  exists.
- GDPR: complaint text, sanctions-hit names and remittance information are personal data. D8 binds
  them to ADR-0175's residency classes, so a hosted non-EU adapter may only receive class-3 data, and
  pseudonymisation does not change the class. A use case that applies an AUTO band to personal data
  needs a DPIA before the band is switched on.
- PSD2: complaint triage changes routing only; the statutory deadline clock of ADR-0117 runs on the
  case regardless of the model's answer, and an `ABSTAIN` leaves it exactly as today.
- CNB: not applicable beyond the above. Complaint registers and the sanctions process keep their
  existing human owners under D4.
- EU AI Act: the triage and ranking uses here are not credit decisions; ADR-0216 stays authoritative
  for credit, and D4 forbids this class from producing a credit outcome at all.

## References

- [ADR-0139](0139-ml-decisioning-platform.md) — in-process ONNX class; its LLM-as-decisioner rejection
- [ADR-0141](0141-model-registry-provenance.md) — model card the engine ref resolves against
- [ADR-0148](0148-ai-assurance-prompt-registry-evals-gate-and-eu-ai-act-mapping.md) — evals registry D7 extends
- [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) — third-party register and exit positions
- [ADR-0175](0175-data-residency-and-sovereignty.md) — residency classes and the `model-residency-claims` gate
- [ADR-0213](0213-deterministic-credit-policy-decision-engine.md) — the deterministic-floor pattern D4 reuses
- [ADR-0322](0322-structured-decision-log-envelope-for-automated-decisions.md) — `DecisionRecord`, extended by D6
- [ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md), [ADR-0085](0085-complaints-handling.md), [ADR-0117](0117-dispute-and-complaint-lifecycle.md) — the first candidate consumers
