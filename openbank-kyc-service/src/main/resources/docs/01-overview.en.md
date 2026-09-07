# Overview

## What the service does

`openbank-kyc-service` is the **case-management system of record for Know Your Customer (KYC) / Customer Due Diligence (CDD)** in the OpenBank platform. It holds:

- **KycCase aggregate** — `partyId`, case status (OPEN / DOCUMENTS_REQUIRED / UNDER_REVIEW / APPROVED / REJECTED / EXPIRED) (`DOCUMENTS_REQUIRED` is declared but unreachable — no `KycService` operation sets it, see #8535; kept because dropping a value from a published enum is a breaking contract narrowing, #8618), risk level (LOW / MEDIUM / HIGH / VERY_HIGH), due-diligence level (SDD / CDD / EDD), reviewer, expiry, and a list of checks.
- **KycCheck** — the per-check result inside a case: IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING, ADVERSE_MEDIA, each with a status (PENDING / PASSED / FAILED / MANUAL_REVIEW), an optional result note and provider reference.
- **Compliance enrichment fields** (V2) — source of funds / wealth, business purpose, expected turnover, PEP self-declaration, beneficial owner, screening provider/reference, periodic next-review date and escalation metadata.

A case is opened either by an operator (`POST /api/v1/kyc/cases`) or automatically when a `PARTY_CREATED` event arrives from `party-service` (ADR-0068 onboarding cockpit). The case then accumulates check results until an authorised reviewer approves or rejects it under a four-eyes control.

## What the service **does NOT** do

- ❌ Does not run the actual sanctions / PEP screening — it records the **result** of a screen; the screening engine is `sanctions-service` / `aml-service`.
- ❌ Does not own customer master data (name, birth number, address) — that is `party-service`. KYC stores only the `partyId` and compliance findings.
- ❌ Does not open bank accounts — that is `account-service`. KYC clearance is a precondition consumed by the onboarding flow.
- ❌ Does not perform per-request idempotency caching — uniqueness is enforced at the domain level (one active case per party).
- ❌ Does not, in production, auto-approve cases — the sandbox straight-through path (`openbank.kyc.auto-approve`) MUST stay `false` in prod; four-eyes is the only approval path (ADR-0068).

## Position in the domain

```
   ┌──────────────┐  PARTY_CREATED        ┌─────────────┐
   │ party-service│ ───────────────────►  │ kyc-service │
   └──────────────┘  (openbank.party.events)└─────┬──────┘
                                                   │ outbox → Kafka
   ┌────────────┐  POST /kyc/cases                 │ (openbank.kyc.events)
   │  admin UI  │ ───────────────────────────────► │
   │ (operators)│  approve / reject (four-eyes)    ▼
   └────────────┘                          ┌────────────────┐
                                           │ party-service  │ (activation)
                                           │ aml-service    │ (triggers)
                                           │ notification   │ (events)
                                           │ audit-service  │ (evidence)
                                           └────────────────┘
            kyc-service → PostgreSQL (database: openbank_kyc)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Auto-open a case when a party is created | — (consumes `PARTY_CREATED`) | `KYC_CASE_OPENED` |
| Open a KYC case manually | `POST /api/v1/kyc/cases` | `KYC_CASE_OPENED` |
| List cases by funnel stage (cockpit) | `GET /api/v1/kyc/cases?status=…` | — |
| Get a case by id / by party | `GET /api/v1/kyc/cases/{id}`, `GET /api/v1/kyc/cases/party/{partyId}` | — |
| Record a check result | `PUT /api/v1/kyc/cases/{id}/checks/{checkType}` | `KYC_CASE_STATUS_CHANGED` (on transition) |
| Approve a case (four-eyes) | `POST /api/v1/kyc/cases/{id}/approve` | `KYC_CASE_APPROVED` |
| Reject a case (four-eyes) | `POST /api/v1/kyc/cases/{id}/reject` | `KYC_CASE_REJECTED` |

## Callers

- **admin-ui** (via Keycloak token) — KYC officers, compliance ops, the onboarding cockpit funnel (ADR-0068)
- **party-service** (events) — upstream producer of `PARTY_CREATED`; downstream consumer of approval for activation
- **aml-service / sanctions-service** — consume KYC events to trigger or correlate screening
- **service-to-service readers** (`ROLE_API`) — read a party's KYC status during onboarding

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_kyc`)
- **Kafka** (`openbank-kafka`, outgoing topic `openbank.kyc.events`, incoming topic `openbank.party.events`)
- **Keycloak** — OIDC auth
- **OPA sidecar** — advisory authorization (ADR-0034)
- **openbank-libs** — `ApiError`/`ErrorCode`, `@Authorize`, BuildInfo/ServiceInfo, DocsResource, outbox plumbing

## Business value

- **Single source of truth** for a party's KYC/CDD state and the audit-grade history of how that decision was reached.
- **Onboarding automation** — a case opens automatically on party creation, feeding the onboarding cockpit funnel without manual ticketing (ADR-0068).
- **Four-eyes compliance** — approve/reject is an authorised, audited dual-control action backed by `@Authorize` and emitted domain events.
- **Eventual consistency** — outbox + Kafka propagate KYC decisions to party activation, AML, notification and audit in near real time.
