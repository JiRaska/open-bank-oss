# Overview

## Co služba dělá

`openbank-account-service` je **systém záznamu (system of record) pro definici účtů** v OpenBank platformě. Drží:

- **Account aggregate** — IBAN, měna, vlastník (party-id), typ účtu (CURRENT / SAVINGS / TERM_DEPOSIT / TECHNICAL), stav (ACTIVE / FROZEN / CLOSED).
- **AccountAuthorization** — kdo má jaké oprávnění k účtu (OWNER / SIGNATORY / VIEWER / TECHNICAL).
- **AccountBalance** — denormalizovaný "rychlý" zůstatek pro UI; **autoritativní zdroj je `openbank-balance-service`** (event-driven sync).

## Co služba **nedělá**

- ❌ Nepočítá zůstatky z transakcí — to dělá `balance-service` čtením z `transaction-service`.
- ❌ Nedělá double-entry book — to je `ledger-service` (technické GL účty).
- ❌ Nevykonává platby — `payment` služby vytvoří transakci, ta zaúčtuje v ledger, balance se přepočítá.
- ❌ Nevydává karty — `card-issuance-service`.
- ❌ Nedělá KYC/AML check při založení účtu — to dělají `kyc-service` / `aml-service` na základě eventu `AccountOpened`.

## Pozice v doméně

```
   ┌────────────┐  AccountOpened    ┌─────────────┐
   │   admin UI │ ───────────────►  │ kyc-service │
   └─────┬──────┘                   └─────────────┘
         │ POST /accounts
         ▼
   ┌─────────────────┐  outbox → Kafka  ┌────────────────┐
   │ account-service │ ───────────────► │ balance-service│
   └────┬────────────┘                  │ ledger-service │
        │                               │ audit-service  │
        ▼                               │ notification   │
    PostgreSQL                          └────────────────┘
   (schema: account)
```

## Klíčové use-cases

| Use-case | API | Event |
|---|---|---|
| Otevři účet pro klienta | `POST /api/v1/accounts` | `AccountOpened` |
| Zafrkni účet (Court order, AML) | `POST /api/v1/accounts/{id}/freeze` | `AccountFrozen` |
| Rozmrazit účet | `POST /api/v1/accounts/{id}/unfreeze` | `AccountUnfrozen` |
| Uzavři účet | `POST /api/v1/accounts/{id}/close` | `AccountClosed` |
| Přidej spoluvlastníka | `POST /api/v1/accounts/{id}/authorizations` | `AuthorizationGranted` |
| Najdi účty pro party | `GET /api/v1/accounts?partyId=…` | — |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři, compliance
- **kyc-service** — read-only kontrola existence účtu při KYC review
- **payment služby** (sepa, domestic, swift, …) — read-only validace IBAN, ověření stavu před platbou
- **balance-service** — read-only pro inicializaci zůstatku nového účtu

## Závislosti

- **PostgreSQL** (`openbank-postgres`, schema `account`)
- **Kafka** (`openbank-kafka`, topic `openbank.account.events.v1`)
- **Redis (Valkey)** — idempotence cache
- **Keycloak** — auth
- **openbank-libs** ≥ 0.1.0 — Money, Iban, AccountId, IdempotencyStore, outbox, BuildInfo, DocsResource

## Hodnota pro byznys

- **Jediný zdroj pravdy** pro existenci a stav účtu — žádné duplicitní seznamy účtů v jiných službách (jen kešované projekce).
- **Audit-trail** — všechny operace nad účtem emitují doménové eventy, které `audit-service` perzistuje pro 10-leté zákonné období.
- **Real-time propagace** přes outbox + Kafka — downstream služby (balance, notifications) mají eventually-consistent view do desítek ms.
- **Compliance-ready** — freeze/unfreeze workflow pro court orders, AML hold, sanctions hit.
