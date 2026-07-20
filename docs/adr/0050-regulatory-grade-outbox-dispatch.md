---
date: 2026-05-31
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kafka, ledger, resilience, compliance]
summary: "Outbox dispatch becomes regulatory-grade: the scheduled dispatcher runs on the Vert.x event loop, keys Kafka records by aggregate, and carries a stable event id so consumers can dedupe at-least-once delivery."
---

# Regulatory-grade transactional-outbox dispatch

## Context

The transactional outbox (ADR-0003) is the money-path's integrity bridge: a state
change and the intent to publish its event commit in the **same** database
transaction, and a dispatcher later relays unpublished rows to Kafka. ADR-0013
moved the invariant primitives into `openbank-libs` so every service shares one
implementation.

While bringing `ledger-service` up in the sandbox, the dispatcher was observed to
fail on **every** scheduled tick with Hibernate Reactive error `HR000068`
("session must be opened on the Vert.x event-loop thread"). No outbox event was
ever published. Because `ledger-service` is the source of truth for the
double-entry sub-ledger, this means a posted ledger movement durably commits to
the database but its event **never** reaches downstream projections
(balance-service) or the audit/clearing path. That is a silent
completeness/timeliness failure on the money path — exactly the class of defect
that BCBS 239 and the EBA ICT guidelines exist to prevent.

A conceptual review (not just a stack-trace fix) surfaced five findings. They are
numbered N1–N5 and each is mapped to a regulatory obligation below, because a
money-path control must be defensible to the ČNB and EBA, not merely "green in
the logs".

**Findings**

- **N1 — Dispatch never publishes (threading).** The `@Scheduled` method returns
  `void`, so Quarkus runs it on a **worker** thread, then `runBlocking { … }`
  drives reactive Panache (`Panache.withSession/withTransaction`) which *must* run
  on the Vert.x **event-loop** thread → `HR000068` on every tick. Net effect: the
  outbox does not drain. Integrity of the committed ledger row is intact, but the
  **propagation** of that fact is 100% broken.
  *Regulatory hook: BCBS 239 §3 (completeness, timeliness); EBA/GL/2019/04 §3.3.3
  (ICT operations — data integrity in processing); DORA Art. 25 (ICT change must
  not degrade operational resilience).*

- **N2 — Non-deterministic Kafka key.** The publisher sends
  `Record.of(UUID.randomUUID(), payload)`. A random partition key destroys
  per-aggregate ordering and breaks the consumer's ability to deduplicate or to
  reason about causal order of postings on one account. ADR-0003 explicitly
  requires idempotency keyed by `event.id`; a random key silently contradicts the
  accepted design.
  *Regulatory hook: BCBS 239 §3 (accuracy & integrity — out-of-order postings
  misstate an account); EBA/GL/2019/04 §3.4 (data integrity in transit).*

- **N3 — At-least-once with no consumer-visible idempotency key.** The relay is
  inherently at-least-once (publish can succeed, then `markSent` can fail → replay
  on the next tick). Without a stable, event-carried idempotency key the duplicate
  is invisible to consumers, risking double-counting of money movements.
  *Regulatory hook: BCBS 239 §3 (accuracy); EBA/GL/2019/04 §3.6.2 (data quality).*

- **N4 — No single-writer guarantee under scale-out.** `concurrentExecution=SKIP`
  only deduplicates within one JVM. With `replicas>1` (or during a rolling
  deploy's overlap) two dispatchers can claim the same rows. There is no
  `FOR UPDATE SKIP LOCKED` and no documented single-writer invariant.
  *Regulatory hook: EBA/GL/2019/04 §3.3.3 (controlled processing); DORA Art. 25.*

- **N5 — No poison-message bound / DLQ.** A row that always fails to publish is
  retried forever (it stays `FAILED` and is re-selected each tick), with no
  attempt cap, no terminal state, and no operator alert. A single poison row can
  starve the batch and hide a stuck money-path event indefinitely.
  *Regulatory hook: DORA Art. 17 (ICT-related incident detection); EBA/GL/2019/04
  §3.7 (ICT operations — monitoring & incident handling).*

This ADR records the regulatory-grade design before any code is written, so the
chosen controls — not the convenience of a quick patch — drive the fix.

## Decision

We will make the shared outbox dispatch **event-loop-correct, deterministically
keyed, idempotent, single-writer, and bounded**, fixing ledger-service first
(money-path priority) with the same corrected pattern that the fleet adopts via
the established per-service sweep (ADR-0013).

1. **N1 — Run dispatch on the event loop.** The `@Scheduled` method returns
   `Uni<Void>`, so Quarkus schedules it as non-blocking and subscribes on the
   Vert.x event loop, where reactive Panache is legal. The **entire dispatch chain
   is reactive Mutiny** — there is no `runBlocking` and no coroutine→worker bridge,
   so a session is never opened off the event-loop thread (the `HR000068` cause).
   Fault-Tolerance annotations (`@Retry/@CircuitBreaker/@Timeout/@Bulkhead`) live on
   a separately-injected publisher bean returning `Uni<Void>`, so the MicroProfile
   interceptors fire on the cross-bean proxied call.

2. **N2/N3 — Deterministic key + carried idempotency id.** The Kafka record key is
   the **aggregate id** (preserves per-account ordering on one partition). The
   **`event.id`** is carried as a message header (`ce-id` / `idempotency-key`) so
   consumers can deduplicate exactly as ADR-0003 mandates. The idempotency key is
   sourced from the existing `event_id` column — no schema change. A full `headers`
   JSONB column for arbitrary CloudEvents attributes (the rest of ADR-0003's
   envelope) is a tracked follow-up, not required to close N2/N3.

3. **N4 — Single-writer invariant, explicit.** The enforced control today is the
   combination of `concurrentExecution = SKIP` (no in-JVM overlap) **and**
   `replicas: 1` pinned in the ledger Deployment manifest — together exactly one
   dispatcher claims a row. Entries are dispatched **sequentially** so per-aggregate
   ordering is preserved. A `FOR UPDATE SKIP LOCKED` claim is the tracked refinement
   that makes the invariant safe under any future multi-writer topology; it is not
   needed at `replicas: 1`.

4. **N5 — Bounded retries + terminal DEAD + alert.** After `MAX_ATTEMPTS` (10)
   persisted publish attempts a row transitions to a terminal **`DEAD`** status, is
   excluded from the processable query, and emits a WARN an operator alert can hook.
   `DEAD` reuses the existing `status` column (`VARCHAR(16)`) — **no schema
   migration**. Poison messages can no longer starve the batch or hide silently.
   *Operator-runbook note:* the **per-tick** publish has its own fast in-tick
   Fault-Tolerance retry (`@Retry maxRetries=2`), so a permanently-failing row is
   physically attempted up to `MAX_ATTEMPTS × (1 + 2) = 30` times before `DEAD`,
   but its persisted `attempt_count` — the value the `DEAD` threshold and alerts key
   off — advances by **one per tick**, reaching `MAX_ATTEMPTS = 10`. A cleanly
   dispatched row ends at `attempt_count = 1` (the success path stamps one attempt).

5. **Reconcile with ADR-0003.** ADR-0003's *reference* mechanism (Debezium CDC)
   and the *real* implementation (libs poller, ADR-0013) have drifted. We affirm
   the **poller** as the implementation of record for now and update ADR-0003's
   status note to point at ADR-0013/0050; the *invariants* ADR-0003 asserts
   (idempotency by `event.id`, `headers`, ordering) are preserved by this design,
   not abandoned.

## Alternatives considered

- **A — Minimal patch: bridge with `Dispatchers.Unconfined`.** Keep `runBlocking`
  but switch the coroutine dispatcher so the reactive call resumes on the event
  loop. *Rejected:* it "works" by accident of which thread resumes the
  continuation, is not auditable for a money-path control, and a future refactor
  silently reintroduces `HR000068`. Not defensible to a regulator.

- **B — Make the scheduled method blocking and use the blocking Panache API.**
  Run on a worker thread and use `@Blocking` + classic (non-reactive) Hibernate.
  *Rejected:* ledger-service is reactive end-to-end (Hibernate Reactive); adding a
  parallel blocking persistence path doubles the data-access surface and its test
  burden on the money path.

- **C — Move to Debezium CDC now (ADR-0003's literal design).** Tail the WAL
  instead of polling. *Rejected for this change:* large operational addition
  (connector, Kafka Connect, schema registry wiring) out of scope for a runtime
  correctness fix; tracked separately. The poller already satisfies the
  invariants once N1–N5 are fixed.

- **D — Per-service bespoke fix.** Fix only ledger-service. *Rejected:* the broken
  `runBlocking` pattern is fleet-wide (≈24 services). Fixing it in `openbank-libs`
  (ADR-0013) fixes it once and ratchets the rest as they are touched.

## Consequences

**Positive**
- Ledger events actually publish; balance projection and audit/clearing paths
  receive postings (closes the N1 completeness gap).
- Deterministic ordering + carried `event.id` make downstream dedup real
  (N2/N3), matching ADR-0003's stated contract.
- Concurrent/rolling dispatch is safe (N4); poison messages are bounded and
  visible (N5).
- One libs fix ratchets the whole fleet (ADR-0013 intent realized).

**Negative**
- Per-service in-place fix (not a single shared `openbank-libs` change): the broken
  `runBlocking` pattern is copy-pasted across the fleet and `openbank-libs`' outbox
  helper has no consumers yet, so each service is corrected and verified
  independently (the established "fleet sweep = 1 PR per service" recipe).
- Single-writer currently rests on `replicas: 1` + in-JVM `SKIP`; a true
  multi-writer claim (`FOR UPDATE SKIP LOCKED`) is deferred.

**Neutral**
- Dispatcher remains poller-based (not Debezium); the CDC migration stays a
  separate, tracked decision.
- No DB migration: `DEAD` reuses the existing `status` column; the idempotency key
  reuses the existing `event_id` column.
- FT annotations stay on a separately-injected publisher bean so the MicroProfile
  interceptors still fire on the cross-bean call.

## Compliance impact

- PCI DSS: not applicable (no cardholder data on the ledger event path).
- DORA:    Art. 25 (ICT change must not degrade resilience — N1/N4),
           Art. 17 (incident detection — N5 DLQ/alert).
- GDPR:    not applicable (no new personal data; account ids already in scope).
- PSD2:    not applicable directly (no PSU-facing API change).
- CNB:     Vyhláška 163/2014 Sb. §8 (řízení rizik ICT) and the internal control
           system requirements — the outbox is a key integrity control on the
           money path; this ADR documents its correctness and monitoring.
- Cross-cutting standards: BCBS 239 §3 (accuracy/integrity, completeness,
  timeliness — N1/N2/N3); EBA/GL/2019/04 §3.3–3.7 (ICT operations, data
  integrity, monitoring — N1–N5).

## References

- ADR-0003 (transactional outbox for Kafka) — invariants this design preserves.
- ADR-0013 (shared outbox in openbank-libs) — where the fix lands.
- ADR-0009 (Postgres per service), ADR-0002 (hexagonal — port stays framework-agnostic).
- docs/threat-models/openbank-ledger-service.md (companion threat model).
- BCBS 239; EBA/GL/2019/04; DORA (Reg. (EU) 2022/2554); ČNB Vyhláška 163/2014 Sb.
