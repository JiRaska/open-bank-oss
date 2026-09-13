# Architecture

## C4 — System Context

```mermaid
graph LR
  pay[payment-services<br/>sepa / domestic / fx]
  acc[account-service]
  kyc[kyc-service]
  admin[admin-ui]
  audit[audit-service]
  aml[aml-service]

  san[(sanctions-service)]:::svc
  db[(PostgreSQL<br/>schema: openbank_sanctions)]
  kafka[(Kafka<br/>sanctions.screening.event)]
  redis[(Valkey<br/>idempotency)]

  pay -- "POST /screen" --> san
  acc -- "POST /screen" --> san
  kyc -- "POST /screen" --> san
  admin -- "GET /hits, /pending<br/>POST /review" --> san

  san --> db
  san -- "outbox → publish" --> kafka
  san --> redis

  kafka --> audit
  kafka --> aml

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-sanctions-service (Quarkus)"
    direction TB
    rest[REST<br/>SanctionsResource<br/>SanctionsListResource]
    uc[Application<br/>SanctionsService<br/>SanctionsListService]
    dom[Domain<br/>SanctionsCheck / SanctionsList<br/>SanctionsMatch + enums]
    persist[Persistence<br/>SanctionsRepositoryImpl<br/>SanctionsListRepositoryImpl<br/>JPA / Panache]
    outbox[Outbox<br/>SanctionsOutboxDispatcher<br/>polls every 500ms]
    idem[Idempotency<br/>Redis / idempotency_key dedup]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  rest --> idem

  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
  idem -.-> redis[(Valkey)]
```

## Hexagonal layers

```
com.openbank.sanctions/
├── domain/                        ◄── core — no framework dependencies
│   └── model/                     SanctionsCheck, SanctionsList, SanctionsMatch
│                                  SanctionsCheckStatus, SanctionsListType, MatchType, EntityType
│
├── application/                   ◄── use-case orchestration
│   ├── port/in/                   SanctionsPorts (inbound commands)
│   ├── port/out/                  SanctionsOutboxPort + SanctionsPorts (outbound)
│   └── usecase/                   SanctionsService, SanctionsListService
│
└── infrastructure/                ◄── adapters
    ├── rest/                      SanctionsResource, SanctionsListResource (JAX-RS)
    ├── persistence/
    │   ├── entity/                SanctionsCheckEntity, SanctionsListEntity, SanctionsOutboxEntity
    │   ├── mapper/                SanctionsMapper (entity ↔ domain)
    │   └── repository/            SanctionsRepositoryImpl, SanctionsListRepositoryImpl,
    │                              SanctionsOutboxRepositoryImpl
    ├── outbox/                    SanctionsOutboxDispatcher (scheduled)
    └── kafka/                     KafkaSanctionsOutboxEventPublisher
```

**Dependency rule:** `domain` ← `application` ← `infrastructure`. Domain code never sees JPA, Kafka, or REST DTOs.

## Screening algorithm

When `POST /api/v1/sanctions/screen` is called, `SanctionsService` runs the following pipeline:

```mermaid
sequenceDiagram
  participant C as Caller (payment-svc)
  participant R as SanctionsResource
  participant S as SanctionsService
  participant DB as PostgreSQL
  participant D as SanctionsOutboxDispatcher
  participant K as Kafka

  C->>R: POST /screen {idempotencyKey, entityType, name, ...}
  R->>R: Check idempotency_key in Redis
  alt Key already seen
    R-->>C: 201 (cached result)
  else New request
    R->>S: screenEntity(cmd)
    S->>S: Fuzzy-match name + aliases<br/>against each enabled SanctionsList
    S->>S: Compute overallScore (max of individual match scores)
    S->>S: Determine status: CLEAR / POTENTIAL_HIT / HIT
    S->>DB: INSERT sanctions_checks + INSERT sanctions_outbox (PENDING)
    R-->>C: 201 {id, status, overallScore, matches}
    loop every 500ms
      D->>DB: SELECT FROM sanctions_outbox WHERE status=PENDING
      D->>K: publish to openbank.sanctions.screening.event
      D->>DB: UPDATE status=PUBLISHED
    end
  end
```

### Match scoring

| Match type | Score range | Trigger |
|---|---|---|
| `EXACT` | 1.0 | Name or alias is identical (case-insensitive, diacritics-normalised) |
| `FUZZY` | 0.7–0.99 | Levenshtein distance ≤ 2 on names with ≥ 5 chars |
| `PHONETIC` | 0.6–0.89 | Soundex / double-metaphone match |
| `ALIAS` | 0.5–0.95 | Known alias in the list entry matches |

`overallScore` = max of all individual match scores across all lists.

Status assignment:
- `overallScore == 1.0` → `HIT`
- `overallScore > 0.85` → `POTENTIAL_HIT`
- `overallScore ≤ 0.85` → `CLEAR`
- After human review: `WHITELISTED` or `ESCALATED`

Domain method `SanctionsCheck.isHighRisk()` returns `true` for `HIT` or `POTENTIAL_HIT` with score > 0.85.

## Outbox flow

**Why outbox:** transactional consistency between the DB write and Kafka publish. At-least-once delivery, idempotent consumers.

```
SanctionsService writes to sanctions_checks AND sanctions_outbox in one TX
    ↓ (within 500ms)
SanctionsOutboxDispatcher polls PENDING rows
    ↓
KafkaSanctionsOutboxEventPublisher sends to openbank.sanctions.screening.event
    ↓
audit-service + aml-service consume the event
```

## Components from `openbank-libs`

| Module | Use here |
|---|---|
| `libs.idempotency.IdempotencyStore` | Redis-backed deduplication by `idempotencyKey` |
| `libs.persistence.outbox` | OutboxEntity base, OutboxRepository, OutboxDispatcherBase |
| `libs.security.BootstrapVerifier` — ⬜ **not consumed, does not exist** | **Nothing.** This class is not in `openbank-libs` and never was (`git grep BootstrapVerifier -- '*.kt'` returns 0); ADR-0017 prescribes it and its own delivery note records that it was not shipped. Dev passwords are kept out of prod by ESO/OpenBao `secretKeyRef` injection (ADR-0007), not by libs code (#8426) |
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata) |
| `libs.docs.DocsResource` | **this documentation** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime tech-stack snapshot |

## Principles

1. **Screening is synchronous, publication is async** — the caller gets the result immediately; Kafka notification is via outbox.
2. **Idempotent at edge** — mandatory `idempotencyKey` in request body; same key returns cached result.
3. **Human-in-the-loop for fuzzy matches** — `POTENTIAL_HIT` never auto-blocks; requires compliance review.
4. **No list data stored** — only match metadata; list entries remain at their authoritative source URLs.
5. **PII minimisation** — `name` and `dateOfBirth` are PII; masked in logs.
