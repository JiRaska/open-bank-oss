---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [audit, ai-agents, authz, compliance]
summary: "Automated decisions emit one core DecisionRecord (libs.audit.decision); state-changing ALLOWs go via the outbox transport proposed as ADR-0323 (#10926) with the business tx, AUTHZ denies via a non-transactional rate-bounded aggregated path."
followup: "none — decision-only until DecisionRecord lands in the libs platform core and the first producers adopt it; delivery tracked by the linked issue"
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

We will define one envelope, `DecisionRecord`, in the libs **platform core** under
`com.openbank.libs.audit.decision` (next to `AuditEvent`), and require every automated decision in
the four classes below to emit it as the `payload` of an `AuditEvent` to audit-service (ADR-0133
chain), over the transport D2 assigns to it.

**Why not `com.openbank.libs.decision`.** ADR-0317 moves that package (credit
`PolicyEvaluation`/`PolicyDecision`) into `openbank-libs-lending` (open PR #10971), and its
`libs-core-purity` gate forbids core modules from depending on a bounded-context module. D2 needs
`AuthorizeInterceptor` in openbank-libs-runtime to build a `DecisionRecord`, so the envelope must
live in core; the credit types in libs-lending map *onto* it (lending depends on core, never the
reverse).

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
| `atomic` | `true` when the record committed in the same transaction as the change it explains; `false` on the non-transactional path (D2) |
| `retentionClass` | closed enum mapped to ADR-0118 periods; credit follows ADR-0214 D4 |

`PolicyEvaluation` becomes a producer of this envelope (a mapping, not a rename), and
`AuthzDecision.policyVersion` fills `engine.version` for `AUTHZ`.

**D2 — Transport: two paths, chosen by whether the decision accompanies a state change.**

- **Transactional path — decisions that accompany a state change.** `FRAUD`, `CREDIT` and
  `AGENT` decisions, and `AUTHZ` `ALLOW`s of state-changing actions (non-GET, or an action the
  `role_action_matrix` marks as a write), are written through the service's transactional outbox
  in the *same* business transaction, so the record commits or rolls back with the change it
  explains. This is the producer-side hash-linked outbox transport proposed as ADR-0323 in #10926 (still
  open; this path depends on it landing); this ADR adds no second audit transport. Read-only `ALLOW`s are recorded on money-path services and for
  `AI_AGENT` principals, sampled elsewhere, with the sampling rate carried on the record.

  **Carrier for `AUTHZ` `ALLOW`s.** The allow is decided in `AuthorizeInterceptor` *before* the
  business transaction opens, so the interceptor cannot write it transactionally itself. It
  builds the `DecisionRecord` and stores it as a **pending record** in a `@RequestScoped`
  `PendingDecisionRecords` holder (libs-runtime), then proceeds. The service's outbox writer —
  the same call that writes the business event inside the use case's transaction — drains the
  holder and appends each pending record to the outbox in that transaction, so it commits or
  rolls back with the change. If the request ends with records still pending (the use case wrote
  no outbox row: a validation 4xx, an idempotent replay, a path with no event), a request-end hook
  emits them on the non-transactional path below with `atomic = false`, so an allow is never
  silently dropped and never claimed to be atomic when it was not. The reactive (Panache
  `withTransaction`) services have no JTA `TransactionSynchronization` to hang this on, which is
  why the drain is explicit in the outbox writer rather than a transaction callback.
  **Services with no datasource or outbox** have no transaction to join: their state-changing
  allows go straight to the non-transactional path with `atomic = false` on the record. The
  `atomic` flag is part of the envelope, so a reader of the chain can tell which allows were
  committed with their change.
- **Non-transactional, rate-bounded path — every `AUTHZ` `DENY`.** A deny happens in
  `AuthorizeInterceptor` *before* any business transaction exists; there is no state change to
  commit with; some services have no datasource at all; and routing an unauthenticated deny into
  a DB write would turn every rejected request into an INSERT — a denial-of-service amplifier.
  Denies therefore never touch the outbox or the service database. They go through the
  `AuditEventPublisher` port on an asynchronous, bounded path:
  - **Aggregation.** Denies are folded in memory per
    `(principalKey, action, reason, engine.version)` over a 60 s window and emitted as one record
    with `count`, `firstAt`, `lastAt`; `principalKey` for an unauthenticated caller is
    `ANONYMOUS` plus the source-network bucket, never a raw token.
  - **Per-principal rate limit.** At most 1 aggregated record per key per window and at most
    10 distinct keys per principal per window; excess keys collapse into one `overflow` record
    carrying the dropped count.
  - **Global bound.** The in-memory buffer is capped (default 10 000 keys per pod); on overflow
    the oldest window is flushed early and further denies are counted, not buffered. Loss is
    therefore bounded and *visible*: the dropped count is on the next record and on a
    `openbank_authz_deny_records_dropped_total` counter.
  - **Always recorded individually:** a deny for an `AI_AGENT` principal and a deny on a
    money-path service's write action — still on this non-transactional path. These exempt classes
    are not aggregated, so they get **their own buffer cap** (default 1 000 records per pod,
    separate from the 10 000-key aggregate buffer, so a flood of anonymous denies cannot evict
    them) and, on overflow, **count toward the same drop counter**, labelled
    `class="exempt"` vs `class="aggregated"` on `openbank_authz_deny_records_dropped_total`. An
    exempt record is never dropped invisibly.

  Today the only `AuditEventPublisher` implementation is the logging fallback, and no asynchronous
  publisher exists; delivering this path includes that publisher (a bounded queue feeding the
  audit topic, not the outbox). Until it lands, the deny path reaches the log pipeline only and
  is not in the ADR-0133 chain — this ADR does not claim otherwise.

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
- Audit volume grows; D2 sampling for non-money-path read `ALLOW`s and deny aggregation are the
  levers.
- Aggregated deny records are lossy by design under flood: a bounded, counted loss is the
  trade for never letting unauthenticated traffic drive database writes.
- Every producer needs a canonical input serialisation for `inputDigest`, which is real work per
  decision type.

**Neutral**
- Enforcement: a new checker `check-decision-record-emitted.py` (gate `decision-record-envelope`,
  advisory first) requires that a module implementing `PolicyDecisionPoint`, producing
  `PolicyDecision`, or declaring a fraud or agent decision port also emits `DecisionRecord`; the
  envelope's closed enums are a type, so shape drift fails compilation.

### Delivery check

- `git grep -n 'class DecisionRecord' -- '*/src/main/kotlin/com/openbank/libs/audit/decision/*'`
  prints one line, in a platform-core module (not `openbank-libs-lending`).
- `AuthorizeInterceptor` contains no outbox or repository reference on its deny path.
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
  ADR-0216, ADR-0226, ADR-0317 (libs split, `libs-core-purity`), ADR-0323 (proposed in #10926; hash-linked audit via
  outbox, PR #10926)
- `com/openbank/libs/decision/PolicyDecision.kt` — in openbank-libs-domain on `main` today,
  moving to `openbank-libs-lending` under ADR-0317 / PR #10971
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/authz/PolicyDecisionPoint.kt`
