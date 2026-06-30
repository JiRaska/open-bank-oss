# ADR-0140 — Feature store topology and point-in-time correctness

Date: 2026-06-29
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Partial    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): Jiri Raska

## Context

ADR-0139 commits OpenBank to a real-time ML decisioning platform fed *only* from
existing domain events. This ADR specifies the part of that platform that is easiest
to get subtly, silently wrong: the **feature store** — how a feature is computed,
written, served and reconstructed-as-of-a-past-instant.

A feature store has two read paths with conflicting requirements:

- **Online** — read at inference time, inside the synchronous money path (fraud
  scoring). Single-key lookup, p99 budget in **single-digit milliseconds**, must be
  available or the decision degrades to rules-only (ADR-0139 fail-closed floor).
- **Offline** — read at training/backfill time. Bulk, point-in-time-correct joins
  over months of history; latency irrelevant, *correctness absolute*.

The failure mode that kills ML projects is **training/serving skew**: the feature a
model is *trained* on differs from the feature it is *served*, because the two paths
compute it from different code, different data, or — most insidiously — different
*time semantics*. The classic instance is **label leakage**: a feature used to
predict an event at time `t` is accidentally computed from data that only existed at
`t + Δ` (e.g. "transactions in the last 24h" materialised *after* the fraudulent
burst). The model looks brilliant offline and fails in production.

OpenBank has one large asset here and one real constraint:

- **Asset:** the Kafka transactional outbox on every event-producing service
  (ADR-0003/0013) is already an ordered, append-only, replayable event log with
  per-event timestamps. It is, in effect, the offline feature source — no new
  ingestion pipeline (ADR-0139 rejected building one). `openbank-fraud-service` is a
  pure *consumer* of those events (it has no outbox of its own); it already maintains
  velocity aggregates (`VelocityAggregate`, with `H1` / `H24` / `D7` windows) that are
  the first features to formalise — `H1`/`H24` in phase 1, `D7` folded in as the
  catalogue expands.
- **Constraint:** there is no stream-processing platform in the stack (no Flink/
  Kafka Streams cluster), and ADR-0027 forbids managed feature stores (SageMaker
  Feature Store / Vertex). Whatever we build is in-cluster OSS and must not require a
  new heavyweight runtime for phase 1.

## Decision

Adopt a **dual-store feature topology with a single feature definition**, where
**point-in-time correctness is a property of the offline path by construction**, and
**online/offline parity is enforced by a shared computation, not by convention.**

**One definition, two materialisations.** A feature is declared once as a pure
function `compute(events_up_to(t)) → value` in the platform library (ADR-0122
runtime split, zero framework imports). The same function is used to (a) update the
online store incrementally as events arrive and (b) reconstruct the value offline by
replaying the event log up to an arbitrary `t`. Two code paths computing "the same"
feature is **forbidden** — that is the skew source. The declaration carries the
feature's name, type, **freshness/TTL**, the event types it consumes and an explicit
**as-of timestamp source** (the event's business time, never wall-clock-at-write).

**Online store — Valkey (already in the stack).**
- Keyed `feature:<name>:<entity-id>` → value + the as-of event timestamp + the
  source event offset (for staleness detection and audit).
- Updated by a consumer of the existing outbox topics; writes are **idempotent on
  event offset** (replays and at-least-once delivery must not double-count — the
  same defect class as outbox dispatch, ADR-0013).
- Served read-only in the money path with a **freshness assertion**: if the stored
  as-of timestamp is older than the feature's TTL, the feature is returned as
  `Stale` and the decisioning engine treats it as *missing* (rules-only floor), never
  as a confident value. A silent stale feature is worse than an absent one.

**Offline store — event-log-derived, point-in-time by replay.**
- The source of truth is the Kafka log itself (retained/compacted) plus a periodic
  **materialised snapshot** to columnar files (Parquet on the in-cluster object
  store, MinIO/S3-compatible) so training does not replay all of history every run.
- A **training row** is built by an as-of join: for a label event at business time
  `t`, every feature is reconstructed from events with business time **strictly `< t`**.
  The strict bound is the anti-leakage invariant and is the single most important
  rule in this ADR.
- The snapshotter records, per feature row, the max event offset consumed — so a
  training set is fully reproducible (replay to the same offsets ⇒ identical rows),
  which feeds model provenance (ADR-0141) and the DST reproducibility story
  (ADR-0100).

**Parity is tested, not assumed.** A CI check replays a sample of historical entities
through *both* the online updater and the offline reconstruction and asserts
bit-identical values. Skew is caught as a test failure, not as a production model
regression. This is the offline analogue of the ADR-0139 shadow mode. **Until this
check is first green, online/offline parity is an assertion, not yet a structural
property** — a green parity check is a hard phase-1 acceptance criterion, not a
nice-to-have.

**Phasing (named, not hidden).**
- *Phase 1 (with ADR-0139 phase 1)* — formalise the existing fraud velocity
  aggregates as the first declared features; online updater over the outbox; Valkey
  store; freshness assertion; the parity CI check. **No new feature types** — just
  lift what `VelocityAggregate` already computes into the shared definition so the
  pattern is proven on known-good data.
- *Phase 2* — offline snapshotter + as-of join + the columnar snapshot; first
  trained model consumes it (pairs with ADR-0139 phase 2 registry/drift).
- *Phase 3* — expand the feature catalogue beyond velocity (counterparty history,
  device/channel, account-age, balance-trajectory) as enforcement turns on
  (ADR-0139 phase 3) under the money-path gate.
- *Deferred* — a stream-processing runtime (Kafka Streams/Flink) **iff** incremental
  online updates outgrow a single consumer; explicitly not phase 1, to avoid a new
  heavyweight runtime before it is justified.

## Alternatives considered

- **Single online-only store, no offline reconstruction.** Train on whatever the
  online store happens to hold. Simplest, and the fastest way to ship leakage — there
  is no way to reconstruct "what did this feature read at decision time three months
  ago". Rejected; point-in-time correctness is non-negotiable for a regulated
  decision.
- **Managed feature store (SageMaker / Vertex / Tecton).** Solves point-in-time out
  of the box, but breaks cloud-agnostic in-cluster OSS (ADR-0027) and exports
  customer risk features to a managed plane. Rejected.
- **Feast (OSS feature store).** Closest off-the-shelf fit and a reasonable phase-2+
  adoption, but it still needs an offline store and an online store wired to *our*
  event log, and pulls a non-trivial operational surface for phase 1's single
  feature family. Kept as a candidate to revisit at phase 3 rather than adopted up
  front; the feature-definition abstraction here is deliberately Feast-shaped so the
  later swap is mechanical.
- **Two separate online/offline computations kept in sync by review.** The
  industry-standard way to ship skew. Rejected outright — parity must be structural
  (one function) and tested, never a convention.
- **Wall-clock-at-write as the as-of timestamp.** Simpler, and wrong: it conflates
  when an event was *processed* with when it *happened*, reintroducing leakage under
  replay/backfill. Rejected — business time from the event is the only correct as-of
  source.

## Consequences

**Positive**
- Point-in-time correctness and online/offline parity are properties of the design,
  not of reviewer vigilance — the two highest-probability ML failure modes are
  closed structurally.
- Reuses the existing outbox event log and Valkey; no new ingestion pipeline and no
  new heavyweight runtime in phase 1.
- Reproducible training sets (offset-pinned) feed model provenance (ADR-0141) and the
  deterministic-simulation story (ADR-0100).

**Negative**
- A feature must be expressed as a pure `compute(events_up_to(t))` function — more
  constraining than ad-hoc SQL, and some features (cross-entity graph signals) are
  awkward to express incrementally; those wait for the deferred stream runtime.
- Offline replay/snapshot is operationally non-trivial at volume (storage, snapshot
  cadence, log retention vs. compaction trade-offs).

**Neutral**
- No money path change in phase 1 (features are computed and stored; ADR-0139 shadow
  mode decides nothing).
- Lands in the `openbank-libs` runtime split (ADR-0122) → fleet-rebuild/libs-merge
  friction applies.

## Compliance impact

- **PCI DSS:** feature definitions are whitelisted; PAN/CVV are not permissible event
  inputs — enforced at the declaration boundary, not by downstream filtering.
- **DORA:** the online store is a money-path dependency — it must have a defined
  degradation mode (the freshness assertion → rules-only floor) and be covered by
  resilience testing.
- **GDPR:** the offline event-log snapshot is personal-data-derived → it inherits the
  retention and erasure obligations of ADR-0118 (GDPR data lifecycle); a feature
  snapshot is not a loophole around erasure. Purpose limitation: features are used for
  the declared decisioning purpose only.
- **PSD2 / ČNB:** point-in-time reconstruction is what makes a past automated decision
  auditable and reproducible — a regulatory asset, not just an engineering one.

## References

- ADR-0139 — real-time ML decisioning platform (this is its feature-store spec)
- ADR-0084 — fraud bounded context (`VelocityAggregate`, the first features)
- ADR-0003 / ADR-0013 — transactional outbox + shared libs primitives (the event log used as the feature source)
- ADR-0027 — cloud-agnostic, in-cluster OSS (rejects managed feature stores)
- ADR-0122 — libs domain/runtime split (where feature definitions live)
- ADR-0118 — GDPR data lifecycle (offline snapshot retention/erasure obligations)
- ADR-0100 — deterministic simulation testing (offset-pinned reproducible training sets)
- ADR-0141 — model registry & provenance (consumes reproducible training-set identity)
