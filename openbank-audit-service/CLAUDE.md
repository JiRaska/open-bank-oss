# openbank-audit-service — service notes

## Engineering notes

- **Never hash a value the database stores at lower precision than the JVM holds it.** The
  tamper-evidence chain (ADR-0031 / ADR-0133) writes `record_hash` from the INCOMING
  `AuditEntry` and re-derives it in `verifyChain()` from the object rebuilt out of the row, so
  the two sides agree only if every hashed field survives the round-trip byte for byte.
  `occurred_at`/`recorded_at` are `TIMESTAMPTZ` — microseconds — while `java.time.Instant`
  carries nanoseconds, and on Linux (where the pods run, unlike macOS) `Instant.now()` really
  does produce them. Hashing the unrounded value and storing the truncated one made every link
  permanently unverifiable: the lost digits are not in the database, so no rendering of the
  stored value can reproduce the hash. `openbank_audit_chain_entries_checked` sat at **0**
  against 1066 chained rows, and `AuditChainBroken` was a permanent critical, from the day the
  V5 chain shipped (#3505). `AuditRepository.normalisedForStorage()` now truncates to
  microseconds on both the persist path and inside `chainHash`, so the two agree by
  construction rather than by luck of the platform clock. If a future field is added to the
  canonical list, ask what the column does to it before adding it.
- **A hash-chain test that does not touch a real database proves nothing about the chain.**
  Every unit test here hashes and verifies in-process, which is exactly the step where the two
  sides disagreed — all of them were green for the whole life of the defect. `AuditChainRoundTripIT`
  (Testcontainers, `PostgresTestResource`) is the one that can see it; it pins the instants to
  literal NANOSECOND values so it proves the same thing on a developer's macOS as on a Linux
  runner. Note `audit_entries` carries `no_update_audit`/`no_delete_audit` rules (V2), so a test
  cannot clean up after itself — write with fresh `aggregate_id`s and anchor `verifyChain` with
  `fromEntryId` instead of assuming an empty table.
- **A consumer that takes `payload: String` cannot see the envelope, and its `?:` defaults make the
  gap look like data.** `AuditConsumer` read the body only, so the outbox `ce-type` header (the
  event type) and the topic (the producing service) were dropped at ingest and fell through to
  `?: "UNKNOWN"` / `?: "unknown"`. Measured on the live database: **1353 of 1774 rows** named no
  source and 131 no event type, with only TWO distinct `source_service` values in the whole table —
  `unknown` and `customer-edge`, the one producer that populates the field. Nothing could see it:
  the fallbacks are *successful parses* (no exception, no metric, no log line), and every existing
  assertion was `isNotNull()`-shaped, which a default satisfies. **Assert the VALUE of an
  attribution field, never its non-nullity** — same lesson as `Instant.EPOCH` in the root guide.
  Fixed in #3994 by taking `Message<String>`, exactly as the analytics sink did in #2598.
- **Do not derive a service name from the topic by convention here — nine of the twenty-one
  subscribed topics disagree with it.** `openbank.cards.events` is card-issuance-service,
  `openbank.payments.swift.event` is swift-service, `openbank.customer.audit` is customer-edge,
  `openbank.security.*` is security-scanner, and accounts/transactions/documents are plural where
  the module is singular. A convention-based derivation does not under-attribute, it writes a
  confident FALSE service name into a chain-hashed evidentiary row — worse than the `unknown` it
  replaces. `TopicAttribution` is a verified table whose COVERAGE (not values) is derived from
  `application.yaml`, so a newly subscribed topic fails `TopicAttributionCoverageTest` instead of
  silently defaulting again.
- **Event time vs ingest time: the per-topic disposition, and why it is HERE and not in the
  consumer's KDoc** (#8352, sweeping #3883). `AuditConsumer.eventTime` reads `occurredAt` and only
  `occurredAt`; absent or unparseable ⇒ the row stores the consumer's clock, flagged
  `occurred_at_source = INGEST`. As of 2026-09-03 the channel subscribes to **27** topics (not the
  21 of #3883) and every one of them carries a top-level `occurredAt` on every production-reachable
  event type. Four event types did not until this sweep, and all four had the instant already in
  hand and simply never projected it onto the wire: `dispute.opened` (had it as `openedAt`),
  `PARTY_ERASED` (as `erasedAt`, while `PartyEvent.occurredAt` held the same value and fed the
  outbox row's `createdAt`), and interest-service's `interest.withholding.recorded.v1` /
  `interest.withholding.remitted.v1` (nothing on the wire; the values are the withholding row's and
  the remittance batch's own `createdAt`). **No topic on this channel genuinely lacks a business
  time** — the honest-INGEST case the issue allowed for did not turn out to exist here.
  Two things about that sweep are the reusable part:
  - **Enumerate the producing TYPE, never grep the key.** Payloads are built four ways on this
    channel — a serialised `DomainEvent` subclass (cards, delegation, ledger, consent, account,
    transaction, balance, domestic-payment, fx, document), a `mapOf`/`ObjectNode` envelope (party,
    kyc, clearing, sca, swift, sepa-instant, customer-edge, ICT), a raw JSON string template
    (dispute, statement, lending, sdd, interest, fraud, sepa-payment's Temporal activities), and
    sanctions' hybrid (serialise the aggregate, then `put` the per-event instant). A grep for
    `"occurredAt"` sees only the last two families and reports a confident false gap over the first.
  - **A `has(key)` assertion cannot see the wire form.** `PartyEventEnvelopeContractTest` built its
    mapper with `findAndRegisterModules()` alone, which leaves `WRITE_DATES_AS_TIMESTAMPS` ON, so
    every `Instant` in it serialised as the number `1.7866E9` — a shape no consumer of that topic
    receives, since Quarkus defaults the property to false. Nothing failed, because every time
    assertion in the file was `json.has(...)`, true of a number as readily as of an ISO-8601 string.
    Assert the VALUE. The same trap cost #3926 a red run on document-service.
- **Asking the audit DATABASE which fields producers send measures traffic, not the wire contract —
  and it answers "nothing to recover" with total confidence.** Chasing the actor half of #3994, the
  obvious probe was to enumerate the payload keys of every actor-less row
  (`jsonb_object_keys(payload::jsonb)` filtered for `%by%`/`%actor%`/`%initiat%`). It returned two
  keys, one of them already read and the other (`reviewedBy`) JSON-null on all 53 rows — i.e. every
  gap is a producer omission and the consumer is fine. **Wrong.** Enumerating the producers'
  serialised TYPES instead found three actor spellings genuinely on the wire and unread —
  `reviewedBy` (sanctions' four-eyes review identity), `changedBy` (3 of 4 card event types) and
  `actorKind` (lending, beside an `actorId` that WAS read, so the row named the actor but not
  whether it was a human or the policy engine). The probe missed them because cards, lending and
  account-service's savings path have **zero rows in the sandbox**, and no manual sanctions review
  has ever run there. Same shape as the zero-denials-from-an-idle-service trap: a topic with no
  traffic is not evidence about that topic. Note both halves of the trap fire here — a `grep` for
  the quoted key finds nothing either, because all three live in serialised data classes where the
  JSON key exists only as a Kotlin property name at runtime. **Enumerate the producing types; use
  the database only to size what you found.**
- **Switching an `@Incoming` from `String` to `Message<String>` switches SmallRye from auto-ack to
  MANUAL ack.** A missed ack stalls the partition and the audit trail stops dead — worse than the
  under-attribution being fixed. Ack explicitly, in a `finally`, and test it on the path most
  likely to skip it (an unparseable payload).
- **`source_service` is not a stable key for `lending`, and only for `lending`.** Twenty of the
  fleet's twenty-one producers emit their module directory name minus `openbank-` (#5256, now
  enforced by `check-source-service-convention.py`); `openbank-lending-service` deliberately emits
  `"lending"` while `TopicAttribution` maps `openbank.lending.events` to `"lending-service"`. Six of
  its nine event types therefore changed spelling mid-stream when #5399 gave them their own
  `sourceService` on 2026-08-18, and read `unknown` before #4270. Any `GROUP BY source_service`
  splits that one producer into three aliases. The decision to keep `"lending"` rather than rename
  and re-split is #5902; the windows, the six event types and the reconciliation query are written
  down in `openbank-lending-service/CLAUDE.md`. Nothing to fix here — just do not read a
  `source_service` histogram as a producer census without it.
