# openbank-sdd-service

Debtor-side **SEPA Direct Debit** for the multi-currency current account (**ADR-0036**). Hexagonal
(ADR-0002): the domain (`domain/`) is framework-free and owns the mandate state machine, the
fail-closed collection-authorisation policy and the refund-window arithmetic; infrastructure owns the
mandate vault, the outbox and the REST surface.

## The shape (read ADR-0036 before changing behaviour)

- **A mandate is the aggregate, not a payment.** Identity is the rulebook pair
  `(creditorIdentifier, umr)`. It binds one account + EUR pocket and has its own lifecycle
  (`PENDING_CONFIRMATION → ACTIVE → SUSPENDED ⇄ ACTIVE → CANCELLED`, auto-`EXPIRED` after 36 idle
  months). **Core** mandates are born `ACTIVE`; **B2B** are born `PENDING_CONFIRMATION` and must be
  confirmed by the debtor bank before they can authorise a collection (rulebook requirement).
- **Authorisation is fail-closed and pure.** `CollectionAuthorisationPolicy` returns
  `Accept | Reject | Refuse` from, in order: mandate present & ACTIVE → scheme match → EUR-only →
  B2B verified → one-off-reuse → debtor controls (block-all / block-list / amount cap). Mandate
  faults `Reject` (technical); debtor controls `Refuse` (debtor right). EPC reason codes attached.
- **v1 never moves money.** An `Accept` stamps the collection on the mandate and emits
  `sdd.collection.authorised` for the ledger/payment path to execute — the irreversible posting
  stays with services already hardened for it (so this service is **not** money-path in v1).
- **Refund windows are computed, never guessed.** `RefundPolicy`: authorised Core ⇒ unconditional
  refund ≤ 8 weeks (56 days); authorised B2B ⇒ none; unauthorised ⇒ 13 months regardless of scheme
  (PSD2 Art. 73/76/77, CZ §177).
- **Pre-notification is tracked, not enforced** (the creditor's ≥14-day duty); a missing one is a
  documented refusal ground.

## Layout

- `domain/model` — `SddMandate` aggregate + enums + `MandateAmendment`.
- `domain/lifecycle` — `MandateLifecycle` (pure transitions + idle-expiry).
- `domain/authorise` — `CollectionAuthorisationPolicy` (fail-closed decision).
- `domain/refund` — `RefundPolicy` (8-week / 13-month windows).
- `application/usecase/SddMandateService` — register / confirm / manage / amend / authorise / refund.
- `infrastructure/{rest,scheduler,outbox,persistence}` — driving/driven adapters.

## Build / test

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew :openbank-sdd-service:test --offline
```

Reactive (`io.smallrye.mutiny.Uni`), not suspend. Port 8129 (mgmt 8086). DB `openbank_sdd`.
Events on `openbank.sdd.event`.

## Out of scope (v1)

Creditor-side issuing (we collect from others) and CSM/clearing connectivity; actual debit/refund
posting (delegated, a money-path fast-follow that will need a threat model per ADR-0030); CZ domestic
*souhlas/povolení k inkasu* (CERTIS), a separate instrument. Account enumeration for the idle-expiry
sweep is wired (`listLive`) but the cron is disabled by default.
