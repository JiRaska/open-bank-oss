# Overview

## What the service does

`openbank-pid-service` (PID = **Party Identity Data**) is the **system of record for the identity of a party** in the OpenBank platform — the "one person / one legal entity = one party" anchor. It holds:

- **Party aggregate** — internal `id` (UUID), `partyType` (NATURAL_PERSON / LEGAL_ENTITY / SOLE_TRADER), `status` (ACTIVE / SUSPENDED / DECEASED / TERMINATED), `version` (optimistic lock).
- **CoreAttributes** — given/family name, birthdate, encrypted birth number (rodné číslo), gender, birthplace, nationalities, ID documents, and the `verificationSource` (BANKID / BRANCH_MANUAL / API_UPLOAD / ROB) with `verifiedAt`.
- **ExternalIds** — the cross-system identifiers that resolve to one party: `BANKID_SUB`, `ROB_AIFO`, `ICO`, `KEYCLOAK_ID`, `PASSPORT_NUMBER`, `ID_CARD_NUMBER`. Each `(type, value)` pair is globally unique.
- **AddressAttributes** — permanent / mailing address with RUIAN code, synced from ROB (Registr obyvatel / ISZR).
- **ContactAttributes** — email, phone (with verification timestamps), preferred language, data-box ID (datová schránka).
- **KycAttributes** — KYC level (NONE / BASIC / ENHANCED / FULL), AML risk score (LOW / MEDIUM / HIGH / UNACCEPTABLE), PEP flag, sanctions flag, UBO + last-AML-review timestamps. These are **stored** attributes set by upstream KYC/AML services — not computed here.
- **Relationships** — the roles a party plays toward the bank (CUSTOMER / EMPLOYEE / ADMIN / AGENT / GUARANTOR / AUTHORIZED_PERSON), each with an onboarding channel and lifecycle status.
- **PID case lifecycle** — a verification case (`CaseType.PID_VERIFICATION`) driven through `libs.domain.case.CaseTransitionEngine` (DRAFT → OPEN → IN_REVIEW → APPROVED/REJECTED/…), with actor, reason code, and evidence-link hooks.

## What the service **does NOT** do

- ❌ Does not open, hold, or close bank accounts — that's `openbank-account-service`.
- ❌ Does not run the KYC/AML decisioning workflow or sanctions screening — `kyc-service` / `aml-service` / `sanctions-service` do; pid-service only *stores* the resulting KYC level, risk score, PEP and sanctions flags.
- ❌ Does not authenticate users or mint tokens — Keycloak is the IdP; pid-service links a `KEYCLOAK_ID` / `BANKID_SUB`.
- ❌ Does not call bankID or ROB/ISZR directly in this codebase — the `/sync/bankid` and `/sync/rob` endpoints accept already-fetched attributes from the caller (the external-registry adapter lives upstream).
- ❌ Does not deduplicate identities by birth-number blind index yet — the identity-unification dedup design (one-person = one-party) is a roadmap item; today creation is deduplicated only on the unique bankID `sub`.

## Position in the domain

```
   ┌────────────┐   POST /parties           ┌──────────────────┐
   │  admin UI  │ ────────────────────────► │  pid-service     │
   └─────┬──────┘   (employee/admin)        │  (Party Identity)│
         │                                   └───────┬──────────┘
   ┌─────┴──────┐   POST /sync/bankid                │ outbox → Kafka
   │ onboarding │ ─────────────────────────►         │  topic: party.events
   │  / IdP     │                                     ▼
   └────────────┘                          ┌───────────────────────┐
                                           │ account-service       │
   GET /parties/{id}  ◄──── account /       │ kyc-service / aml      │
   GET /by-external-id ◄─── kyc / payment   │ audit-service          │
                                            │ notification           │
              PostgreSQL                    └───────────────────────┘
            (db: openbank_pid)
```

## Key use cases

| Use case | API | Event(s) emitted |
|---|---|---|
| Create a party (unified identity) | `POST /api/v1/parties` | `PartyCreated`, `case.created`, `RelationshipAdded` |
| Get party by internal id | `GET /api/v1/parties/{id}` | — |
| Resolve party by external id | `GET /api/v1/parties/by-external-id?type=&value=` | — |
| Search parties | `GET /api/v1/parties?familyName=…&role=…` | — |
| Sync attributes from bankID | `POST /api/v1/parties/{id}/sync/bankid` | `PartyVerified` |
| Sync address from ROB | `POST /api/v1/parties/{id}/sync/rob` | `AddressUpdatedFromRob` |
| Update contact info | `PATCH /api/v1/parties/{id}/contact` | — |
| Update KYC/AML attributes | `PUT /api/v1/parties/{id}/kyc` | `KycLevelChanged` (only if level changed) |
| Change party status | `PATCH /api/v1/parties/{id}/status` | `PartyStatusChanged` |
| Transition PID verification case | `PATCH /api/v1/parties/{id}/case` | `case.transitioned` |
| Add a role/relationship | `POST /api/v1/parties/{id}/relationships` | `RelationshipAdded` |
| Terminate a relationship | `DELETE /api/v1/parties/{id}/relationships/{relationshipId}` | `RelationshipTerminated` |

## Callers

- **admin-ui** (via Keycloak token) — operators / compliance create, search, and curate parties.
- **onboarding / customer app** — feeds verified bankID attributes (the `/sync/bankid` path) and reads own profile (`openbank-customer` role on `GET /{id}` and `PATCH /contact`).
- **account-service / payment services** — read-only resolution of `partyId` and external ids before linking an account or processing a payment.
- **kyc / aml / sanctions services** — consume `PartyCreated` / `PartyVerified` events and push back KYC/AML attributes via `PUT /kyc`.

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_pid`)
- **Kafka** (`openbank-kafka`, topic `party.events`)
- **Keycloak** — OIDC auth
- **OPA sidecar** (advisory) — `@Authorize` decisions (ADR-0034)
- **openbank-libs** — `libs.domain.case` (CaseTransitionEngine, CaseId, CaseStatus, CaseReasonCode, CaseType), `libs.domain.event.DomainEvent`, `libs.authz.@Authorize`, `libs.api.error.ApiError`, `libs.docs.DocsResource`, `libs.web.ServiceInfoResource`

## Business value

- **Single source of truth for identity** — one canonical party record across the bank; account/payment/KYC services reference a `partyId` instead of duplicating personal data.
- **Cross-registry resolution** — `by-external-id` maps a bankID `sub`, ROB AIFO, or IČO to the one internal party, the foundation for the "one person = one party" identity-unification goal.
- **Auditable identity lifecycle** — every identity, KYC, status, and relationship change emits a domain event for the audit trail; the PID verification case lifecycle gives compliance an explainable approval/rejection history.
- **Regulator-aligned data model** — bankID, ROB/ISZR, RUIAN, data-box, birth-number encryption and PEP/sanctions flags map directly onto Czech AML/KYC and eIDAS expectations.
