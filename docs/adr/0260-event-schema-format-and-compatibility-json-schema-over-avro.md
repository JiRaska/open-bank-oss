---
date: 2026-08-16
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kafka, api-contract, governance, documents]
summary: "Amends ADR-0006: event schemas are JSON Schema (not Avro), enforced BACKWARD_TRANSITIVE fleet-wide and FULL_TRANSITIVE on money-path topics, with the committed .schema.json file — not the Apicurio registry — as source of truth."
followup: "#1916 — pilot ships observe-only for one topic; producer-side enforcement, header-discriminator coverage, and the money-path rollout are not built"
---

# 260. Event schema format and compatibility: JSON Schema over Avro

**This ADR amends ADR-0006.** ADR-0006 deliberately left open "Avro or JSON Schema" and said
the choice "should be settled here (or in a successor ADR) before the first schema is
registered" (tracked in #1916). This is that successor ADR. It does not reopen anything else
ADR-0006 decided — AsyncAPI 3.0 as the documentation format, Apicurio as the registry, the
`-vN` topic convention for breaking changes — it resolves the one question ADR-0006 left
unresolved, and registers the first schema under the answer.

## Context

Re-measured against `origin/main` on 2026-08-16, immediately before writing this ADR:

- Apicurio Registry is deployed and healthy (`openbank-infra/gitops/apps/apicurio.yaml`,
  its own CNPG Postgres) and holds **zero registered artifacts** — confirmed live via
  `kubectl port-forward svc/apicurio-registry -n messaging` and
  `GET /apis/registry/v2/search/artifacts` returning `{"artifacts": [], "count": 0}`. The gap
  ADR-0006 identified — registration and enforcement, not provisioning — is unchanged.
- `check-event-contract-coverage.py` now measures **43 producer:topic pairs**, 8 with an
  AsyncAPI contract, 35 grandfathered in `.github/event-contract-baseline.txt` — up from 42/7/35
  at the time #1916 was last triaged; the fleet gained one contract and one producer pair in the
  interim, consistent with several parallel PRs landing contracts (delegation, notification, and
  now others). No topic anywhere has a schema registered in any format: `.avsc` count is still 0,
  and JSON Schema count is 0 outside this PR.
- `check-event-schema-compat.py` diffs Kotlin `DomainEvent` subclass constructors and already
  has a dormant `compare_avsc()` path for `.avsc` files, unused because no `.avsc` exists.
  **It is blind by construction to any producer that does not extend `DomainEvent`.** This is
  not hypothetical: `openbank-document-service`'s own `DocumentGenerated` and
  `SignatureCeremonyCompleted` (the events this ADR's pilot covers) are plain data classes with
  no `DomainEvent` supertype, so the gate has never once evaluated a change to either — the same
  structural gap `SepaPaymentCreatedEvent` has on the money-path side.
- The outbox is the delivery mechanism for the large majority of the fleet's producers.
  `PanacheOutboxEntity`/`AbstractOutboxEntity` persist `payload` as a `TEXT` column, written
  inside the business transaction and read back by a generic `AbstractOutboxDispatcher` that
  treats it as an opaque `String` — the dispatcher has no knowledge of Avro, Protobuf, or any
  binary framing, and 27 of 29 grandfathered producers write to it today.
- Two independent version axes already exist and are enforced: the release version
  (`version.txt`, ADR-0029) and the API contract version (`openapi.yaml:info.version`,
  ADR-0048). ADR-0048's D1 table already carries a placeholder row for the event-schema axis
  that names Apicurio as the source of truth; this ADR corrects that row (below) once the format
  and true source of truth are decided.

### Why the format choice cannot be deferred further

Every subsequent ADR-0006 step — a schema per event, a compatibility rule, a CI gate, a
producer-side validator — depends on knowing whether a schema is Avro IDL/`.avsc` or JSON
Schema. The two are not interchangeable at the storage layer this fleet already committed to:

- **Avro or Protobuf** means the payload on the wire changes from human-readable JSON to a
  binary encoding. The outbox's `TEXT` payload column and its opaque-`String` dispatcher assume
  JSON (or at least text); adopting a binary format means either (a) re-encoding to binary at
  the write site — a `TEXT`→`BYTEA` migration across every grandfathered producer's outbox
  table, several of them money-path, with a live-traffic cutover window in which the column
  holds an undecodable mix of pre- and post-migration rows and nothing in the row schema
  discriminates one from the other — or (b) re-encoding at dispatch time, which buys none of
  Avro's write-site type safety (the producer still writes untyped JSON) while adding a decode
  step and a new failure mode (a JSON payload that doesn't map cleanly to the target Avro
  schema) to every dispatch.
- **JSON Schema** validates the payload the fleet already writes. Zero DDL. Zero backlog
  ambiguity: every historical outbox row and every already-consumed Kafka message was, and
  remains, valid JSON: a JSON Schema is checked against it directly, with no re-encoding, no
  migration window, and no discriminator problem.

## Decision

**We will use JSON Schema, not Avro or Protobuf, as the wire schema format for Kafka event
payloads.** This resolves ADR-0006's open "Avro or JSON Schema" line, and downstream
consequences follow from that resolution:

### D1 — Compatibility mode

- **BACKWARD_TRANSITIVE is the fleet-wide floor.** A new schema version must be readable by
  every consumer written against any prior version registered for that subject, not merely the
  immediately preceding one — this platform's audit and replay obligations (DORA Art. 8-10,
  GDPR data-subject requests over historical events) routinely need to read further back than
  one schema generation, so BACKWARD (single-step) is not sufficient.
- **FULL_TRANSITIVE on money-path topics** (`rules.yaml: money_path_services`). Full
  compatibility (both backward and forward, transitively) buys zero-coordination rolling
  deploys: a money-path change already needs 2 approvals and a threat model (ADR-0030), and a
  FULL_TRANSITIVE subject means a producer and its consumers can each roll independently without
  a flag-day cutover, which a plain BACKWARD subject cannot guarantee (an old producer emitting
  under the new schema can still break a not-yet-upgraded consumer under BACKWARD alone).

### D2 — Third version axis: source of truth is the committed file, not the registry

Restating and sharpening ADR-0048 D1's placeholder row: the event-schema version's source of
truth is the **committed** `openbank-contracts/<service>/schema/<event>.schema.json` file, not
the Apicurio-assigned registry version. Apicurio is a **derived artifact** — CI applies the
committed file to the registry idempotently on merge to `main`, the same relationship
`openapi.yaml` already has to whatever renders it. Git history is the approval record (the file
is reviewed exactly like `asyncapi.yaml`/`openapi.yaml` today); no out-of-band registry mutation
is a valid way to change a schema. This is enforced operationally by restricting Apicurio write
access to the CI service account's identity at the NetworkPolicy layer
(`openbank-infra/gitops/components/apicurio/network-policies.yaml`) — a manual `PUT` against the
registry from a human session is not how a schema version changes.

### D3 — Breaking change is a new topic, not an in-place major bump

Unchanged from ADR-0006: a breaking schema change ships as a new versioned event
(`*-v2-events-out` convention) running alongside the old one until migration completes, never an
in-place edit to a registered subject. This is what BACKWARD/FULL_TRANSITIVE is defending —
compatibility mode governs additive/compatible evolution of the *current* schema; anything
outside that envelope is a new topic by construction, so the registry never needs to accept an
incompatible change to an existing subject in the first place.

### D4 — The header-discriminator gap is a known, separately-tracked hole

Several topics — `openbank.sanctions.screening.event` is the sharpest example — carry the event
type ONLY in the `ce-type` Kafka header (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`), never in the
payload body. No payload schema, in any format, can express "this field lives in a header, not
the body." This is not new: `check-asyncapi-doc-discriminator.py` already enforces the
equivalent fact for the fleet's published AsyncAPI document (channel-level discriminator
declarations). The comparable check at the JSON Schema layer — verifying a schema's declared
`x-openbank-event-type` against the literal `eventType`/`ce-type` value a producer actually
constructs — is follow-up work (#1916), not built by this ADR; a topic whose events differ only
by header cannot be fully covered by payload-schema compatibility checking alone until it lands.

## Alternatives considered

- **Avro, as ADR-0006's text originally allowed.** Pros: compact binary encoding; mature schema
  evolution tooling; what Confluent/Apicurio tutorials default to. Cons: requires either a
  `TEXT`→`BYTEA` outbox migration across 27+ live tables (several money-path) with an
  undecodable-mix cutover window, or paying dispatch-time re-encoding complexity with none of
  Avro's write-site type-safety benefit. Rejected — the storage layer this fleet already
  committed to (ADR-0050 transactional outbox, `TEXT` payload) makes Avro materially more
  expensive to adopt than JSON Schema for identical compatibility guarantees.
- **Protobuf.** Same binary-encoding cost as Avro, plus this fleet has no existing Protobuf
  tooling anywhere (no `.proto` files, no codegen wired into any Gradle build). Rejected for the
  same reason as Avro, with less prior art to offset the migration cost.
- **BACKWARD (single-step) compatibility fleet-wide, no FULL_TRANSITIVE tier.** Simpler: one
  mode everywhere. Rejected for money-path topics specifically — money-path already carries a
  higher bar (2 approvals, threat model) and a rolling deploy without a flag-day cutover is worth
  the stronger guarantee there; peripheral topics don't carry the same coordination cost today so
  BACKWARD_TRANSITIVE alone is the right floor for them.
- **Registry-as-source-of-truth (Apicurio version is authoritative, no committed file).** Pros:
  one less artifact to keep in sync. Cons: breaks this repo's established pattern (every other
  contract — `openapi.yaml`, `asyncapi.yaml` — is git-reviewed, and the registry is the
  CI-applied consequence, not the input) and removes the audit trail an evidentiary system needs:
  a registry mutation with no corresponding reviewed diff is exactly the case D2's NetworkPolicy
  restriction exists to prevent. Rejected.

## Consequences

**Positive**
- Unblocks #1916: the format question ADR-0006 left open is now answered, so a schema can be
  authored and registered without re-litigating Avro-vs-JSON-Schema per pilot.
- Zero migration cost to existing infrastructure — the outbox's `TEXT` payload and the
  dispatcher's opaque-`String` handling need no change to support JSON Schema validation.
- FULL_TRANSITIVE on money-path buys coordination-free rolling deploys for the services under
  the strictest review bar, where a flag-day cutover is most expensive to arrange.

**Negative**
- JSON Schema's compatibility rules are less battle-tested in this specific pairing (Apicurio +
  Kafka + JSON Schema) than the Avro path most Schema Registry tooling is written against first;
  `check-event-schema-compat.py`'s new `compare_json_schema()` (this PR) is hand-rolled, not a
  wrapper around a canonical library, and only covers `properties`/`required`/`type` at the top
  level of each `oneOf` branch — nested object/array schema changes are not yet compared.
- The header-discriminator gap (D4) means payload-schema compatibility checking alone cannot
  fully describe every topic; header-carrying topics need the separate check this ADR does not
  build.

**Neutral**
- No change to AsyncAPI-as-documentation, Apicurio-as-registry, or the `-vN` breaking-change
  convention — all three stand as ADR-0006 already decided them.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data path change.
- DORA:    Art. 8-10 (change traceability, resilience testing) — improved for topics that adopt
  a registered schema: BACKWARD_TRANSITIVE is a direct, checkable guarantee that a historical
  event remains replayable, which today rests only on developer discipline.
- GDPR:    not applicable directly, but a registered schema makes it easier to identify which
  historical event versions carry a given personal-data field when responding to an Art. 15/20
  request — a documentation improvement, not a new obligation.
- PSD2:    not applicable — no TPP-facing interface change (this is the internal event bus, not
  the REST API ADR-0048 governs).
- CNB:     improved change traceability for the same reason as DORA above — a registered,
  versioned event schema is a more precise audit artifact than an undocumented JSON payload.

## References

- ADR-0006 — AsyncAPI 3.0 for all Kafka topics (this ADR amends its open "Avro or JSON Schema"
  question; does not change anything else it decided).
- ADR-0048 — Decouple the API contract version from the service release version (D1's
  event-schema-axis row is sharpened by D2 above).
- ADR-0050 — Transactional outbox pattern (the `TEXT` payload column this ADR's cost analysis is
  built on).
- ADR-0030 — Money-path services need 2 approvals + a threat model (why FULL_TRANSITIVE is
  scoped to that tier).
- ADR-0144 — Gate graduation: advisory rules carry an enforcement deadline (the pattern
  `rules.yaml: event_change` already follows, and continues to follow through this ADR).
- `openbank-libs/governance/rules.yaml: event_change` — the gate this ADR's pilot exercises
  (`schema-compat` / `check-event-schema-compat.py`), unchanged in enforcement status by this ADR.
- `openbank-infra/gitops/apps/apicurio.yaml`, `components/apicurio/network-policies.yaml` — the
  registry this ADR designates a derived artifact (D2).
- Issue #1916 — the tracking issue this ADR and its pilot PR close/refine.
