---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [audit, ai-agents, authz, compliance]
summary: "Authz, fraud, credit and AI-agent decisions emit one DecisionRecord envelope (input digest, policy/model version, outcome, reason codes, correlation, retention class) via the outbox to the ADR-0133 audit chain."
followup: "none — decision-only until DecisionRecord lands in openbank-libs-domain and the first producers adopt it; delivery tracked by the linked issue"
---

# ADR-0322 — Structured decision-log envelope for automated decisions

## Context

ADR-0214 decided that every *credit* decision emits an evidence record (table versions, matched
rule ids, outcome, reason codes, input-snapshot hash) into the ADR-0133 tamper-evident chain, and
ADR-0216 maps EU AI Act Art. 12 onto that record — for credit only. ADR-0226 decided the
correlation fields (`channel`, `actChain`) every audit event carries. None of them decides a
common shape for the *other* automated decisions the platform takes, and this ADR is needed
because those decisions today leave no comparable record:

- **Authorization.** `AuthzDecision` (`openbank-libs-domain/.../authz/PolicyDecisionPoint.kt`)
  already carries `allow`, `reason`, `policyVersion` and `attributes`, and `rest.rego` emits
  `policy_version`. But `AuthorizeInterceptor` never references `AuditEventPublisher` — a
  decision reaches a `SecurityTelemetry` counter and nothing else, so "which bundle allowed this
  call" is not answerable after the fact.
- **Credit.** `com.openbank.libs.decision.PolicyEvaluation` already holds `policyVersions`,
  `matchedRuleIds`, `reasons` and `inputSnapshotHash` — the right fields, in a credit-specific type.
- **Fraud and AI agents.** Scores and agent tool decisions are recorded in service-specific shapes
  (ADR-0031 AI-attributed audit, ADR-0139 platform), with no shared field for model version or
  input digest.

`AuditEvent` has a free-form `payload` map, so each producer invents its own keys — the same
two-idiom problem the repo has already measured for event-time fields (#3883).

## Decision

We will define one envelope, `DecisionRecord`, in openbank-libs-domain
(`com.openbank.libs.decision`), and require every automated decision in the four classes below to
emit it as the `payload` of an `AuditEvent` through the service's transactional outbox to
audit-service (ADR-0133 chain).

**D1 — Envelope fields.**

| Field | Meaning |
|---|---|
| `decisionId` | UUIDv7 |
| `decisionClass` | closed enum: `AUTHZ`, `FRAUD`, `CREDIT`, `AGENT` |
| `decidedAt` | measured at decision time, never defaulted |
| `inputDigest` | SHA-256 over a canonical serialisation of the inputs actually evaluated; never the inputs |
| `engine` | `{kind: opa, decision-table, model or llm; id; version}` — OPA bundle version, table versions, model-registry version (ADR-0141) or prompt-registry version (ADR-0148) |
| `outcome` | closed per class (e.g. `ALLOW/DENY`, `APPROVE/REFER/DECLINE`, `PASS/REVIEW/BLOCK`, `PROPOSED/EXECUTED/REFUSED`) |
| `reasons` | list of `{code, ruleId?}` — machine-readable; credit reuses `PolicyReasonCode` |
| `subjectRef` | opaque identifier of the affected party or resource, never a name |
| `correlation` | `traceId`, `correlationId`, `channel`, `actChain` (ADR-0226) |
| `humanReview` | `none`, `pending` or `completed(by, at)` — the oversight state |
| `retentionClass` | closed enum mapped to ADR-0118 periods; credit follows ADR-0214 D4 |

`PolicyEvaluation` becomes a producer of this envelope (a mapping, not a rename), and
`AuthzDecision.policyVersion` fills `engine.version` for `AUTHZ`.

**D2 — Transport.** Through the existing outbox, never a direct Kafka send, so the record commits
with the state change it explains. `AUTHZ` records: every `DENY`, and every decision on a
money-path service or for an `AI_AGENT` principal, is recorded; `ALLOW` elsewhere may be sampled
to bound volume, and the sampling rate is itself carried on the record.

**D3 — Minimisation.** The envelope carries digests, versions and pointers only, as ADR-0214 D2
already requires for credit; erasure under ADR-0118 does not rewrite the chain.

**D4 — ADR-0214 stays authoritative for credit.** This ADR generalises its *shape*; the credit
lifecycle table in ADR-0214 D1 is unchanged, and its policy-evaluation and ML-decision rows are
emitted as `DecisionRecord`s.

## Alternatives considered

- **One envelope per domain (extend the ADR-0214 pattern service by service)** — least
  coordination, but reproduces today's divergence; reconstructing one customer's day would join
  four schemas.
- **OPA native decision logs to a separate sink** — covers authz only, sits outside the ADR-0133
  chain (not tamper-evident, not correlated with the business record) and cannot carry fraud,
  credit or agent decisions.
- **Structured application logs (Loki)** — cheap, but logs are not an evidentiary store: retention
  is operational rather than statutory, and nothing makes them tamper-evident.

## Consequences

**Positive**
- One query answers "which policy or model version decided this, on which inputs" across authz,
  fraud, credit and agents.
- Authorization decisions become auditable at all, not just counted.

**Negative**
- Audit volume grows; D2 sampling for non-money-path `ALLOW` is the lever.
- Every producer needs a canonical input serialisation for `inputDigest`, which is real work per
  decision type.

**Neutral**
- Enforcement: a new checker `check-decision-record-emitted.py` (gate `decision-record-envelope`,
  advisory first) requires that a module implementing `PolicyDecisionPoint`, producing
  `PolicyDecision`, or declaring a fraud or agent decision port also emits `DecisionRecord`; the
  envelope's closed enums are a type, so shape drift fails compilation.

### Delivery check

- `git grep -n 'class DecisionRecord' -- openbank-libs-domain/src/main` prints one line.
- `git grep -c 'DecisionRecord' -- openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz/AuthorizeInterceptor.kt`
  is non-zero (today the file does not reference `AuditEventPublisher` at all).
- On a running audit-service, a denied request produces a chain row whose payload has
  `decisionClass = AUTHZ` and a non-null `engine.version`.

## Compliance impact

- PCI DSS: not applicable — no cardholder data enters the envelope (D3).
- DORA: not applicable — this is decision traceability, not ICT resilience.
- GDPR: Art. 22 — `humanReview` and machine-readable `reasons` record whether a decision was
  solely automated and on what grounds, supporting human intervention and explanation;
  minimisation per D3.
- PSD2: not applicable — no change to payment-initiation or account-access flows.
- CNB: not applicable — no reporting change.

Also relevant: EU AI Act Art. 12 (automatic logging) and Art. 19 (retention of automatically
generated logs) for the high-risk credit AI of ADR-0216, extended here to the same envelope; EBA
Guidelines on loan origination and monitoring — documented, explainable automated credit
decisions, delivered through ADR-0214.

## References

- ADR-0031, ADR-0034, ADR-0118, ADR-0133, ADR-0139, ADR-0141, ADR-0148, ADR-0213, ADR-0214,
  ADR-0216, ADR-0226
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/decision/PolicyDecision.kt`
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/PolicyDecisionPoint.kt`
