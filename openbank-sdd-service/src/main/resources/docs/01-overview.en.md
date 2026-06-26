# Overview

## What the service does

`openbank-sdd-service` is the **debtor-side system of record for SEPA Direct Debit mandates** in the OpenBank platform (ADR-0036). It holds:

- **SddMandate aggregate** — the standing authorisation a customer (debtor) gives a creditor to collect from one account's EUR pocket. Identity is the rulebook pair `(creditorIdentifier, UMR)`. Carries scheme (CORE / B2B), sequence type (OOFF / FRST / RCUR / FNAL), creditor/debtor names, signature date, status and a recorded list of amendments.
- **Mandate lifecycle** — a pure state machine `PENDING_CONFIRMATION → ACTIVE → SUSPENDED ⇄ ACTIVE → CANCELLED`, plus auto-`EXPIRED` after 36 idle months. **Core** mandates are born `ACTIVE`; **B2B** mandates are born `PENDING_CONFIRMATION` and must be confirmed by the debtor bank.
- **Collection authorisation** — a fail-closed, pure decision (`ACCEPT` / `REJECT` / `REFUSE`) over an inbound collection instruction against the stored mandate and debtor controls.
- **Refund assessment** — a computed refund-window decision (8-week unconditional for authorised Core, 13-month for unauthorised, none for authorised B2B).

## What the service **does NOT** do

- ❌ Does not move money — v1 never debits and never posts a refund. An `ACCEPT` only emits `sdd.collection.authorised.v1` for the ledger/payment path to execute (the irreversible posting stays with services already hardened for it).
- ❌ Does not issue creditor-side collections — this service *collects from others*; creditor issuing is out of scope.
- ❌ Does not connect to a CSM / clearing house — CSM connectivity is out of scope in v1.
- ❌ Is not the CZ domestic *souhlas/povolení k inkasu* (CERTIS) — a separate instrument.
- ❌ Does not run AML/sanctions screening — that lives in `aml-service` / `sanctions-service` on the downstream posting path.
- ❌ Does not enforce the creditor pre-notification duty — the ≥14-day pre-notification is *tracked*, not enforced (a missing one is a documented refusal ground).

## Position in the domain

```
   ┌────────────┐  POST /mandates       ┌──────────────────┐
   │  admin UI  │ ───────────────────►  │                  │
   └────────────┘                       │  sdd-service     │
   ┌────────────┐  POST /collections/   │  (mandate vault) │
   │  payment / │  authorise            │                  │
   │  clearing  │ ───────────────────►  └────────┬─────────┘
   └────────────┘                                │ outbox → Kafka
                                                 ▼
                                       ┌──────────────────────┐
   PostgreSQL  ◄──────────────────────┤  openbank.sdd.event   │
   (db: openbank_sdd)                  │  → ledger / payment   │
                                       │  → audit / notify     │
                                       └──────────────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Register a debtor mandate | `POST /api/v1/sdd/mandates` | `sdd.mandate.registered.v1` |
| Confirm a B2B mandate (PENDING_CONFIRMATION → ACTIVE) | `POST /api/v1/sdd/mandates/{id}/confirm` | `sdd.mandate.confirmed.v1` |
| Suspend an ACTIVE mandate | `POST /api/v1/sdd/mandates/{id}/suspend` | `sdd.mandate.suspended.v1` |
| Resume a SUSPENDED mandate | `POST /api/v1/sdd/mandates/{id}/resume` | `sdd.mandate.resumed.v1` |
| Cancel a mandate (terminal) | `POST /api/v1/sdd/mandates/{id}/cancel` | `sdd.mandate.cancelled.v1` |
| Amend a mandate field (AMDT marker) | `PATCH /api/v1/sdd/mandates/{id}` | `sdd.mandate.amended.v1` |
| Authorise an inbound collection | `POST /api/v1/sdd/collections/authorise` | `sdd.collection.authorised.v1` (on ACCEPT) |
| Assess a refund claim | `GET /api/v1/sdd/mandates/{id}/refund-assessment` | — |
| List / fetch mandates | `GET /api/v1/sdd/mandates?accountId=…`, `GET …/{id}` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators, payments ops register/manage mandates.
- **payment / clearing services** — call `POST /collections/authorise` to get a fail-closed decision before they execute the debit.
- **downstream consumers** (ledger/payment, audit, notification) — consume `openbank.sdd.event` (read-only, async).

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_sdd`)
- **Kafka** (`openbank-kafka`, topic `openbank.sdd.event`)
- **Keycloak** — OIDC auth
- **openbank-libs** — shared runtime plumbing (BuildInfo, ServiceInfoResource, DocsResource, security)

## Business value

- **Single source of truth** for the standing direct-debit authorisations a customer has granted — no duplicate mandate lists across services.
- **Fail-closed by design** — when in doubt the collection is rejected/refused, never silently accepted; this is the customer's protection against unauthorised debits.
- **Regulatory-grade refund arithmetic** — refund windows (PSD2 Art. 73/76/77, CZ §177) are computed, not guessed.
- **Eventually-consistent propagation** via the transactional outbox + Kafka — downstream posting, audit and notification see an authorised collection within seconds.
