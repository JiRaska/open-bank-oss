# Overview

## What the service does

`openbank-fx-service` is the **foreign-exchange rate book and conversion engine** of the OpenBank platform. It holds:

- **FxRate** — a quoted currency pair (`baseCurrency`/`quoteCurrency`) with a `bidRate`/`askRate` (and derived `midRate`/`spread`), a `rateType` (SPOT / FORWARD / INDICATIVE / INTERBANK), a `source` (ECB / REUTERS / BLOOMBERG / INTERNAL / CNB) and a validity window (`validFrom`/`validTo`).
- **FxConversion** — an executed conversion request: party, optional account, from/to currency, amounts in minor units, the `appliedRate` and the `rateId` pinned at execution time, a fee, and a lifecycle `status` (PENDING / SETTLED / FAILED / REVERSED).
- **ČNB central-bank fixing** — the daily *kurz devizového trhu* ingested from the ČNB feed, stored as `source = CNB`, `rateType = INDICATIVE` rates quoted in CZK (ADR-0046).

Every conversion is **synchronously screened** against the sanctions lists before it is allowed to settle (ADR-0032). A clean party settles (SETTLED); a sanctions hit fails the conversion (FAILED) and opens a CRITICAL AML case; a sub-threshold potential hit or a screening-service outage holds the conversion in PENDING for human review (fail-closed — never settled un-screened).

## What the service **does NOT** do

- ❌ Does not move money on accounts or post to the ledger — it computes and records a conversion; settlement booking is done by the ledger/transaction/balance services.
- ❌ Does not compute balances — that's `balance-service`.
- ❌ Does not own the sanctions lists or the AML case backend — it *calls* `sanctions-service` (screen) and `aml-service` (open case).
- ❌ Is not a market-data terminal — it stores a small set of rates (internal seed + ČNB fixing for the configured currencies), not a full tick feed.
- ❌ Does not initiate payments — payment services call it for a rate or a conversion.

## Position in the domain

```
   ┌────────────┐   GET /rates / POST /convert   ┌──────────────┐
   │  admin UI  │ ─────────────────────────────► │              │
   └────────────┘                                │              │
   ┌────────────────┐  POST /convert             │  fx-service  │
   │ payment / txn  │ ─────────────────────────► │              │
   └────────────────┘                            └───┬───┬───┬──┘
                                                     │   │   │
   ┌──────────────────┐  POST /sanctions/screen      │   │   │ outbox → Kafka
   │ sanctions-service│ ◄────────────────────────────┘   │   ▼
   └──────────────────┘                                  │ openbank.fx.conversion.completed
   ┌──────────────────┐  POST /aml/cases                 │   │
   │   aml-service    │ ◄────────────────────────────────┘   ▼
   └──────────────────┘                              ┌──────────────┐
   ┌──────────────────┐  daily fixing feed           │  PostgreSQL  │
   │   ČNB feed (ext) │ ─────────────────────────►   │ (openbank_fx)│
   └──────────────────┘                              └──────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| List all current FX rates | `GET /api/v1/fx/rates` | — |
| Get rate for a pair (`?source=CNB` for the central-bank fixing) | `GET /api/v1/fx/rates/{base}/{quote}` | — |
| Execute a currency conversion (screened) | `POST /api/v1/fx/convert` | `FxConversionExecuted` → `openbank.fx.conversion.completed` |
| Read a conversion by id | `GET /api/v1/fx/conversions/{id}` | — |
| Ingest the ČNB fixing for a day (ops/backfill) | `POST /api/v1/fx/cnb/ingest` | — |
| Read the latest ČNB fixing for a currency | `GET /api/v1/fx/cnb/rates/{base}` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators read rates, trigger conversions/backfill.
- **payment / transaction services** — request a rate (`ROLE_PAYMENTS`) or a conversion before/at settlement.
- **operators** — ingest/backfill the ČNB fixing (`ROLE_OPERATOR`/`ROLE_ADMIN`).

## Dependencies

- **PostgreSQL** (database `openbank_fx`, logical schema `fx_schema`) — rates, conversions, outbox.
- **Kafka** — outbox publish to `openbank.fx.conversion.completed`.
- **Redis (Valkey)** — client configured (idempotency on conversions is enforced via the DB unique key).
- **sanctions-service** — synchronous screen of the converting party (ADR-0032 gate).
- **aml-service** — open a CRITICAL/HIGH/MEDIUM AML case on hit / review / screening-unavailable.
- **ČNB feed** (external, `https://www.cnb.cz/...denni_kurz.txt`) — daily central-bank fixing (ADR-0046).
- **Keycloak** — OIDC auth.
- **openbank-libs** — shared web/security/build plumbing, DocsResource.

## Business value

- **One rate book** — internal rates plus the official ČNB fixing in one queryable place, with a pinned `rateId`/timestamp on every conversion for auditability and dispute defence.
- **Compliance-by-construction** — no conversion settles without passing the synchronous sanctions gate; hits and uncertain cases fail closed and create an auditable AML case.
- **Event propagation** — settled conversions are published via the transactional outbox so downstream services have an eventually-consistent view.
