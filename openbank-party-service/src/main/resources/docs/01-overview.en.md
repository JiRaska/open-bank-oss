# Overview

## What the service does

`openbank-party-service` is the **system of record for parties** in the OpenBank platform. A *party* is any natural person or legal entity the bank deals with. It holds:

- **Party aggregate** — `partyType` (INDIVIDUAL / SOLE_TRADER / COMPANY / TRUST), `status` (PENDING_KYC / ACTIVE / SUSPENDED / CLOSED), legal name, optional trading name, contact details (email — unique, phone, address), identity attributes (date of birth, nationality, tax id, registration number), plus compliance metadata (PEP flag, risk rating, FATCA/CRS status, GDPR consent, review dates).
- **Party documents** — identity documents attached to a party (NATIONAL_ID / PASSPORT / DRIVING_LICENCE / COMPANY_REGISTRATION / TAX_ID), with issuing country and expiry.
- **KYC + AML lifecycle state** — `kycStatus` (NOT_STARTED / IN_PROGRESS / APPROVED / REJECTED / EXPIRED) and `amlStatus` (NOT_SCREENED / CLEARED / BLOCKED). The party becomes ACTIVE only when both keys clear (two-key activation gate).

## What the service **does NOT** do

- ❌ Does not run KYC reviews — `kyc-service` owns the case engine; party-service only records the terminal outcome (via REST or the `openbank.kyc.events` stream).
- ❌ Does not run AML / sanctions screening — `aml-service` / `sanctions-service` do; party-service records the outcome from `openbank.aml.events`.
- ❌ Does not store the encrypted birth number (rodné číslo) — that lives in `pid-service`. The birth number is never searchable here (GDPR data-minimisation).
- ❌ Does not open or hold accounts — `account-service` does, keyed by `ownerPartyId`.
- ❌ Does not move money — party-service is not a money-path service.

## Position in the domain

```
   ┌────────────┐  POST/GET /parties   ┌──────────────────┐
   │  admin UI  │ ───────────────────► │  party-service   │
   └────────────┘                      │  (identity SoR)  │
                                        └───┬──────────┬───┘
   ┌────────────┐  kyc-status (REST)        │          │ outbox → Kafka
   │ kyc-service│ ─────────────────────────►│          ▼  openbank.party.events
   └─────┬──────┘                           │     ┌──────────────┐
         │ openbank.kyc.events              │     │ account-svc  │
         ▼ (consumed)                       │     │ audit-service│
   ┌────────────────┐  openbank.aml.events  │     │ onboarding   │
   │  aml-service   │ ─────────────────────►│     └──────────────┘
   └────────────────┘ (consumed)            ▼
                                       PostgreSQL
                                    (db: openbank_parties)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Register a new party (customer/company) | `POST /api/v1/parties` | `PARTY_CREATED` |
| Update party contact details | `PATCH /api/v1/parties/{id}` | `PARTY_UPDATED` |
| Add an identity document | `POST /api/v1/parties/{id}/documents` | — |
| Record terminal KYC outcome | `PUT /api/v1/parties/{id}/kyc-status` | `KYC_STATUS_CHANGED` |
| Record terminal KYC outcome (event) | consumes `openbank.kyc.events` | `KYC_STATUS_CHANGED` |
| Record terminal AML outcome (event) | consumes `openbank.aml.events` | `KYC_STATUS_CHANGED` |
| List parties (paginated, optional status filter) | `GET /api/v1/parties?status=…` | — |
| Search parties by name (trigram) | `GET /api/v1/parties/search?q=…` | — |
| Erase a party (GDPR Art. 17) | `DELETE /api/v1/parties/{id}` | `PARTY_ERASED` |

## Callers

- **admin-ui** (via Keycloak token) — operators, compliance, the onboarding cockpit funnel (ADR-0068 status filter).
- **kyc-service** — pushes terminal KYC decisions (`ROLE_KYC` on `PUT .../kyc-status`) and/or emits to `openbank.kyc.events`.
- **aml-service** — emits terminal AML decisions to `openbank.aml.events`.
- **account-service** — read-only owner lookup (`GET /parties/{id}`) when opening an account.
- **pid-service** — relationship for encrypted document data (downstream).

## Dependencies

- **PostgreSQL** (`openbank_parties` database)
- **Kafka** — outgoing topic `openbank.party.events`; incoming `openbank.kyc.events`, `openbank.aml.events`
- **Keycloak** — OIDC auth
- **flagd** (OpenFeature, ADR-0067) — feature flags `party-search`, `party-list-enriched`; fail-static
- **OPA sidecar** (ADR-0034) — authorization, advisory mode
- **openbank-libs** — ApiError, CursorPage/PageInfo/CursorEncoder, SearchRequest, FeatureClient/@FeatureFlag, @Authorize, outbox plumbing, DocsResource, ServiceInfoResource

## Business value

- **Single source of truth** for who the bank's customers are — every account, payment, and statement ultimately resolves to a `partyId` here.
- **Two-key onboarding gate** — a party is only activated when both KYC (APPROVED) and AML (CLEARED) clear, fail-closed; a hard negative on either suspends it. This makes the activation decision auditable in one place.
- **GDPR-ready** — explicit Right-to-Erasure flow that anonymises PII while preserving the uniqueness/tombstone needed by AML retention.
- **Compliance metadata first-class** — PEP, risk rating, FATCA/CRS, review-due dates live on the aggregate for downstream AML/regulatory reporting.
