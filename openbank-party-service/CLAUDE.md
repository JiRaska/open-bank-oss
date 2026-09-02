# openbank-party-service — service notes

## Party events leave through the outbox, and only through the outbox (#4007)

**Decision: wire the apparatus, do not delete it.**

`party_outbox` shipped complete — Flyway migration, `PartyOutboxDispatcher`, backlog gauge,
`openbank.outbox.dispatch-enabled: true`, an atomic `claimProcessable` — and nothing ever wrote a
row to it. Every lifecycle event (`PARTY_CREATED`, `PARTY_UPDATED`, `KYC_STATUS_CHANGED`,
`PARTY_ERASED`, `PARTY_MERGED`) went out through `KafkaPartyEventPublisher`, a bare
`@Channel("party-events-out")` emitter called *after* the repository transaction had already
committed. That is a dual write: if the emit fails after the row commits the event is lost with no
record, and if it succeeds and the transaction then rolls back a consumer has acted on something
that did not happen. `PARTY_MERGED` (ADR-0179) is the expensive case — a consumer that misses it
keeps writing to a retired identity.

Deleting the apparatus was the other honest option and was rejected: the events are consumed by
account-, aml-, kyc-, onboarding- and copilot-service, several of them on the money path, so the
atomicity is wanted here.

What the wiring looks like now:

- `PartyEvents` (domain) builds the flat JSON envelope. It is the WIRE CONTRACT — the same field
  names, order and flat shape the direct emitter produced — pinned by
  `PartyEventEnvelopeContractTest` and by the provider-side pacts.
- `PartyRepository.save/update/anonymize` have event-carrying overloads. The impl chains
  `outboxRepository.persistInTransaction(...)` inside the SAME `Panache.withTransaction` block as
  the state change, so the row and the event commit together or neither does.
- The direct emitter and its `party-events-out` channel are **removed**. `party-outbox-out`
  publishes to the same topic (`openbank.party.events`), so no consumer sees a difference beyond
  the additive `OutboxKafkaHeaders` and a partition key. Two publishers on one topic would race,
  and only one of them can be atomic.

Three things to keep true when touching this:

- **`openbank.outbox.dispatch-enabled` must stay `true`** in `application.yaml`. It defaults to
  `false`, and when it is false events never dispatch — no error, `attempt_count` stays 0.
- **`persist()` is correct here, `merge()` is not needed.** `PartyEntity` extends `PanacheEntity`,
  so the `@Id` is generated and `save` is a genuine INSERT; `party_id` is a business key, not the
  id. The fleet's application-assigned-id trap (persist is INSERT-only, every lifecycle transition
  dies on the PK) does not apply — but it would the moment someone changes that base class.
- **Only a real-DB IT can prove any of this.** A unit test that mocks the repository cannot tell
  which publisher a use case called, which is exactly why the defect survived a green suite for the
  life of the service. `PartyOutboxWriteIT` drives the REST endpoints (a `Panache.withTransaction`
  repo called from a bare `@QuarkusTest` thread throws "No current Vertx context found" — only an
  HTTP request carries a context) and asserts the row with a plain JDBC read.

## `V8__party_aml_status.sql` cites the wrong ADR, permanently (#5785)

The header comment of `src/main/resources/db/migration/V8__party_aml_status.sql` reads

```
-- AML screening outcome — second key of the KYC+AML activation gate (ADR-0073).
```

**ADR-0073 is "Hardware-backed credential storage for the customer app"** — mobile
Keystore/Keychain secrets, nothing to do with the activation gate. The decision that actually
covers `parties.aml_status` as the second key of the two-key activation gate is
**ADR-0267 — Event-driven onboarding account lifecycle** (§2).

The citation cannot be corrected. Flyway checksums the **whole migration file, comments
included**, so editing an applied migration fails startup with `checksum mismatch`. This note is
the correction; do not "fix" the SQL. If you are reading `V8` for the AML status column, read
ADR-0267, not ADR-0073.
