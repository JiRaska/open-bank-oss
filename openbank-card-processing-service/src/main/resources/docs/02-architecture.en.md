# Architecture

## C4 — System Context

```mermaid
graph LR
  acq["Acquirer / sandbox acquirer"]
  ci["card-issuance-service"]
  fr["fraud-service"]
  tx["transaction-service"]
  visa["Visa Developer Platform (sandbox, optional)"]
  mc["Mastercard Developers (sandbox, optional)"]

  cp["card-processing-service"]:::svc
  db[("PostgreSQL<br/>openbank_card_processing")]
  kafka[("Kafka<br/>openbank.card.processing.events")]

  acq -- "POST /api/v1/card-authorizations" --> cp
  cp -- "GET /api/v1/cards/{id}<br/>POST /api/v1/cards/{id}/authorizations" --> ci
  cp -- "POST /api/v1/fraud/score (shadow)" --> fr
  cp -- "POST /api/v1/transactions (rail CARD)" --> tx
  cp -. "BIN lookup when bound" .-> visa
  cp -. "BIN lookup when bound" .-> mc
  cp --> db
  cp -- "outbox publish" --> kafka

  classDef svc fill:#dbeafe,stroke:#2563eb
```

All outbound calls to platform services authenticate as the service (`openbank-services` client credentials via `quarkus-oidc-client`), because card-processing asks about a card the caller does not own.

## C4 — Container

```mermaid
graph TB
  subgraph "openbank-card-processing-service"
    direction TB
    rest["REST<br/>CardProcessingResource, SandboxAcquirerResource<br/>CardTokenResource, CardDisputeResource"]
    uc["Application<br/>CardProcessingService<br/>CardTokenService, CardDisputeService"]
    dom["Domain<br/>CardAuthorization, AuthorizationLifecycle<br/>SpendWindow, CardProcessingEvent"]
    persist["Persistence<br/>CardAuthorizationRepositoryImpl"]
    clients["Clients<br/>CardIssuanceAdapter, TransactionLedgerPostingAdapter<br/>FraudScoringAdapter"]
    scheme["Scheme ports<br/>Routed BIN / tokenisation / dispute<br/>simulators, Visa + Mastercard BIN adapters"]
    disp["CardProcessingOutboxDispatcher<br/>every 5s"]
    sweep["HoldExpirySweep<br/>cron every 15 min"]
  end
  rest --> uc
  sweep --> uc
  uc --> dom
  uc --> persist
  uc --> clients
  uc --> scheme
  persist -.-> db[("PostgreSQL")]
  disp -.-> db
  disp -.-> kafka[("Kafka")]
```

## Hexagonal layers (ADR-0002)

```
com.openbank.cardprocessing/
├── domain/
│   ├── model/      CardAuthorization, AuthorizationStatus, PresentmentChannel,
│   │               PresentmentRefusal/Outcome, SpendWindow, CountedSpend
│   ├── policy/     AuthorizationLifecycle (clear / reverse / expire — pure, clock passed in)
│   └── event/      CardAuthorised, CardDeclined, CardCleared, CardHoldReleased
├── application/
│   ├── port/in/    CardProcessingUseCase, AuthorizationCommand, PresentmentCommand
│   ├── port/out/   CardAuthorizationRepository, CardLookupPort, CardIssuancePolicyPort,
│   │               LedgerPostingPort, FraudScoringPort, CardProcessingMetricsPort
│   └── usecase/    CardProcessingService
└── infrastructure/
    ├── rest/       CardProcessingResource, DTOs, CardNotFoundExceptionMapper
    ├── processor/  SandboxAcquirerResource
    ├── client/     card-issuance, transaction-service and fraud-service REST clients
    ├── scheme/     Routed*Port, Simulated*Adapter, Visa/Mastercard BIN adapters, OAuth 1.0a signer
    ├── persistence/ entities + repositories
    ├── outbox/     dispatcher, backlog and dead-letter gauges
    ├── kafka/      KafkaCardProcessingOutboxEventPublisher
    ├── scheduler/  HoldExpirySweep
    └── observability/ CardProcessingMetricsAdapter
```

The scheme port interfaces (`BinLookupPort`, `MerchantDataPort`, `TokenisationPort`, `DisputePort`, plus `PushPaymentPort` and `AccountUpdaterPort`) live in `openbank-libs-domain` (`com.openbank.libs.domain.cards.scheme`), so they carry no scheme vocabulary into the service.

## Authorisation flow

```mermaid
sequenceDiagram
  participant A as Acquirer
  participant R as CardProcessingResource
  participant S as CardProcessingService
  participant DB as PostgreSQL
  participant CI as card-issuance
  participant F as fraud-service

  A->>R: POST /api/v1/card-authorizations (Idempotency-Key)
  R->>S: authorize(cmd)
  S->>DB: findByIdempotencyKey
  alt key already seen
    S-->>R: first authorisation, unchanged
  else new
    S->>CI: GET /api/v1/cards/{id} (account, party, currency)
    S->>DB: countSpend(card, day + month window)
    S->>CI: POST /api/v1/cards/{id}/authorizations
    S->>DB: INSERT card_authorizations + card_outbox (one TX)
    S->>F: score (shadow, result ignored)
    S-->>R: APPROVED or DECLINED
  end
  R-->>A: 201 Created
```

Notes from the code:

- Unknown card ⇒ `CardNotFoundException` ⇒ **404**. A currency different from the card's currency, or a non-positive amount ⇒ **400**.
- `CardIssuanceAdapter` **fails closed**: if card-issuance cannot be reached, the authorisation is declined.
- Spend is counted in the database from the authorisation rows (no running-total column). If card-issuance maps the MCC to a category, spend is recounted for that category and the decision is asked again only if the counts differ.
- The merchant name is passed through `MerchantDataPort` (simulator binding); an unresolved descriptor keeps the acquirer's text.
- A hold expires `hold-expiry-days` (default 7) after authorisation.

## Clearing and ledger posting

```mermaid
sequenceDiagram
  participant A as Acquirer
  participant S as CardProcessingService
  participant L as AuthorizationLifecycle
  participant DB as PostgreSQL
  participant T as transaction-service

  A->>S: clear(id, amount, currency, key)
  S->>DB: findClearing(id, key)
  alt key already applied
    S-->>A: 200 replay (same body) or 409 IDEMPOTENCY_KEY_REUSED
  end
  S->>DB: findById
  S->>L: clear(authorization, amount, currency)
  alt refused
    S-->>A: 409 with PresentmentRefusal
  else accepted
    S->>DB: INSERT card_clearings + UPDATE card_authorizations + INSERT card_outbox (card.cleared.v1)
    Note over S,DB: a concurrent duplicate fails ux_card_clearings_authorization_key, rolls back and replays the winner
    S->>T: POST /api/v1/transactions (rail CARD, key card-clearing:AUTH_ID:h:SHA256(KEY))
    S-->>A: 200 with new state
  end
```

A clearing key is applied at most once per authorisation (see [03 — API](./03-api.md#idempotency)); a replay never reaches the ledger again. Every write to an authorisation — clearing, reversal and expiry — goes through one optimistic lock (`card_authorizations.version`), so none can overwrite another computed from an older read. Concurrent clearings under different keys are serialised this way: the loser is re-evaluated once against the real remaining hold, so nothing is applied past the authorised amount.

The posting runs **after** the clearing commits and is never rolled back: the acquirer has already asserted the clearing. Its outcome is three-valued — `POSTED`, `SKIPPED_DISABLED`, `FAILED` — counted in `openbank.card.processing.ledger.postings`, and a `FAILED` posting is logged as an error. Amounts are converted from minor units using the currency's own fraction digits.

## Authorisation states

```mermaid
stateDiagram-v2
  [*] --> APPROVED
  [*] --> DECLINED
  APPROVED --> PARTIALLY_CLEARED: partial clearing
  APPROVED --> CLEARED: full clearing
  PARTIALLY_CLEARED --> PARTIALLY_CLEARED: further partial clearing
  PARTIALLY_CLEARED --> CLEARED: remainder cleared
  APPROVED --> REVERSED: reversal
  PARTIALLY_CLEARED --> REVERSED: reversal
  APPROVED --> EXPIRED: expiry sweep
  PARTIALLY_CLEARED --> EXPIRED: expiry sweep
```

`DECLINED`, `CLEARED`, `REVERSED` and `EXPIRED` are terminal. A reversal of a partially cleared authorisation releases only the unpresented remainder; the cleared amount stays.

## Scheme capability ports

Each port has a router that reads one config key and picks a binding. An unrecognised or vendor value never falls back to the simulator.

| Port | Config key | `simulator` | `visa` | `mastercard` |
|---|---|---|---|---|
| `BinLookupPort` | `openbank.card-processing.scheme.bin-lookup` | deterministic table of the published test ranges (411111, 555555); other BINs ⇒ `NOT_FOUND` | `VisaBinLookupAdapter` — mTLS from key/trust store + API key | `MastercardBinLookupAdapter` — OAuth 1.0a signed request |
| `TokenisationPort` | `openbank.card-processing.scheme.tokenisation` | `SimulatedTokenisationAdapter` | `NOT_BOUND` (VTS is contract-only) | `NOT_BOUND` (MDES is contract-only) |
| `DisputePort` | `openbank.card-processing.scheme.dispute` | `SimulatedDisputeAdapter` | `NOT_BOUND` (VROL is contract-only) | `NOT_BOUND` (Mastercom is contract-only) |
| `MerchantDataPort` | — | `SimulatedSchemeAdapter` (only binding) | — | — |

- A vendor BIN adapter with no credential configured answers `NOT_BOUND` and makes no request. For Mastercard, no signer bean is produced when the consumer key or signing key is missing.
- Results are `SchemeResult` values carrying the `CardScheme` that answered (`VISA`, `MASTERCARD`, `SIMULATOR`) or a `SchemeFailure` (`NOT_BOUND`, `NOT_FOUND`, `UNAVAILABLE`, `UNAUTHENTICATED`, `MALFORMED`).
- **Callers:** `MerchantDataPort` is used by the authorisation flow, `TokenisationPort` by `CardTokenService` and `DisputePort` by `CardDisputeService`. `BinLookupPort` is bound but no use case or REST endpoint consumes it yet. The capability matrix is in [`docs/cards/capability-matrix.md`](../../../../docs/cards/capability-matrix.md).

## Network-token mirror (ADR-0283 phase 3)

`CardTokenService` is the caller of `TokenisationPort`. The table `card_network_tokens` is the bank's **record** that a token exists; the vault belongs to the network.

- **Provision** — the `Idempotency-Key` is reserved in `card_lifecycle_idempotency` first; a completed key replays the first registration, a key still in flight is 409 and never reaches the network. The card must be known to card-issuance (404 `CARD_NOT_FOUND`), card-issuance must be reachable (503 `ISSUER_UNAVAILABLE`, fail closed) and the card must be `ACTIVE` (409 `CARD_NOT_ACTIVE` — a token for a blocked card is a live credential for a card the bank stopped). On an answer from the binding, the row, `card.token.provisioned.v1` and the reservation's completion are written in one transaction. A failure after the network was asked leaves the reservation PENDING, so a retry cannot mint a second token.
- **Change status** — `ACTIVE`, `SUSPENDED` or `DELETED`. `DELETED` is terminal, enforced on the aggregate (`TOKEN_TERMINAL`). The mirror row takes the status the network returns.
- **List** — the network is asked first. If it answers, the response is the network's list with mirror fields (requestor label, provisioning time) merged in and `source: NETWORK`; a token the mirror has never seen is **adopted** into the mirror (insert-if-absent on `token_reference`, requestor label `not recorded by this bank`), so it keeps one stable id across reads; a mirrored token the network did not return is still listed, with `absentAtNetwork: true`. If it does not answer, the response is the mirror with `source: LOCAL_MIRROR` and a `degradedReason`. A live read never rewrites an existing mirror row.
- Scheme failures map to refusals: `NOT_BOUND` / `UNAVAILABLE` / `UNAUTHENTICATED` ⇒ `SCHEME_UNAVAILABLE`, `NOT_FOUND` ⇒ `TOKEN_NOT_FOUND`, `MALFORMED` ⇒ `SCHEME_REFUSED`.

## Dispute desk (ADR-0283 phase 3)

`CardDisputeService` is the caller of `DisputePort`. A case carries two vocabularies: the bank's `status` and the network's `schemeStatus`, stored verbatim.

```mermaid
stateDiagram-v2
  [*] --> OPEN: network assigned a case id
  OPEN --> EVIDENCE_SUBMITTED: evidence filed
  EVIDENCE_SUBMITTED --> EVIDENCE_SUBMITTED: further evidence
  OPEN --> WON: refresh
  OPEN --> LOST: refresh
  OPEN --> WITHDRAWN: refresh
  EVIDENCE_SUBMITTED --> WON: refresh
  EVIDENCE_SUBMITTED --> LOST: refresh
  EVIDENCE_SUBMITTED --> WITHDRAWN: refresh
```

- **Open** — checks, in order: idempotency key reservation (replay / 409 in progress / 409 reused); authorisation exists; something has cleared (`NOTHING_CLEARED`); the currency is the authorisation's (`CURRENCY_MISMATCH`); the amount, compared as Money, is positive and at most the cleared amount (`AMOUNT_EXCEEDS_CLEARED`); no live case (`OPEN` or `EVIDENCE_SUBMITTED`) exists for the authorisation (`ALREADY_DISPUTED`, also enforced by a partial unique index); the authorisation has a network reference (`NO_NETWORK_REFERENCE`). Only then is the network asked. **Opening fails closed**: if the binding does not answer, no row is written.
- **Evidence** — `Idempotency-Key` required and reserved like opening, so a retry never files twice. Refused on a terminal case (`CASE_TERMINAL`); otherwise forwarded to the network, **appended** to `card_dispute_evidence` (one row per filing, never overwritten) and the case moves to `EVIDENCE_SUBMITTED` with `evidenceReference` = the latest document.
- **Refresh** — reads the network status. `WON` / `RESOLVED_WON` / `REPRESENTED_WON` ⇒ `WON`; `LOST` / `RESOLVED_LOST` / `CHARGEBACK_ACCEPTED` ⇒ `LOST`; `WITHDRAWN` / `CANCELLED` ⇒ `WITHDRAWN`; any other value keeps the bank status. An event is written only when either status changed. A **closed** case (`WON`, `LOST`, `WITHDRAWN`) is terminal: refresh returns it unchanged; if the network now reports a different outcome, `openbank.card.dispute.terminal.mismatches` is incremented and a warning logged.
- No money moves on any dispute transition in this service: a won or lost case posts nothing to the ledger.

## Outbox (ADR-0050)

- The authorisation row and its event are written in **one transaction** (`card_authorizations` + `card_outbox`).
- `CardProcessingOutboxDispatcher` runs every `openbank.outbox.poll-interval` (default 5s), `concurrentExecution = SKIP`, with `@Bulkhead`, `@CircuitBreaker`, `@Retry` and `@Timeout`. It is a `suspend fun`, so it has a Vert.x context.
- Kafka key = the aggregate id (authorisation, token registration or dispute case); `ce-id`, `idempotency-key` and `ce-type` headers are set. The `synthetic` column (ADR-0252) is carried into the transport header.
- `openbank.outbox.dispatch-enabled: true` is set in `application.yaml`.

## Principles

1. **Refusals are values, not exceptions** — a repeated or late presentment is ordinary scheme traffic.
2. **Derived, not stored** — the hold balance and the counted spend are computed from rows.
3. **The database restates the money invariants** — CHECK constraints for cleared ≤ authorised and decline-reason-iff-declined.
4. **A skipped or failed side effect is never a success** — posting and scoring have their own `SKIPPED_DISABLED` / `FAILED` outcomes.
5. **No card data** — references by card-issuance id only.
