# Overview

## What the service does

`openbank-product-catalog` is the **system of record for bank products and their pricing**. It owns:

- **Product master** — one record per product (e.g. `SAVINGS_STANDARD`, `CURRENT_PERSONAL`, `LOAN_PERSONAL_5Y`, `MORTGAGE_FIXED_20Y`, `CREDIT_CARD_CLASSIC`, `TERM_DEPOSIT_12M`, `OVERDRAFT_PERSONAL`, multi-currency umbrella). Each carries identity (`code`, `name`, `type`, `currency`), lifecycle `status` (DRAFT / ACTIVE / INACTIVE / DEPRECATED / ARCHIVED), pricing (`baseRate`, `fee`, `fees[]`), eligibility segments, version history and terms-and-conditions.
- **Per-type configuration blocks** — `cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig` (rates, FX margins, card networks/tiers, withdrawal notice, etc.).
- **Bank-wide fee schedule** — every product's fees flattened into one filterable schedule (`FeeScheduleItem`), each line carrying its owning product identity and a derived stable code (e.g. `CURRENT_PERSONAL_FX_CONVERSION`). This is the catalog's own source of truth for pricing so the **admin UI never hardcodes a price list**.

## What the service **does NOT** do

- Does not open or hold accounts — that is `openbank-account-service` (it consumes product definitions).
- Does not move money, post entries or compute balances — `ledger` / `transaction` / `balance` services.
- Does not accrue or pay interest — `interest-service` (the catalog only declares the rates).
- Does not run FX conversion — `fx-service` (the catalog only declares FX margins per product).
- Does not perform pricing/billing execution — it publishes the *schedule*; charging happens in the money-path services.
- Does not issue cards — `card-issuance-service` (the catalog only declares card config: networks, tiers, limits).
- Is not a money-path service — no funds flow through it.

## Position in the domain

```
   ┌────────────┐  GET/POST /products   ┌──────────────────────┐
   │  admin UI  │ ───────────────────►  │ product-catalog (8104)│
   └────────────┘  GET /fees            └─────────┬────────────┘
                                                   │ read product defs / pricing
        account-service ─────────────────────────►│  (product code, type, fees)
        interest / fx / card-issuance ────────────►│  (rates, FX margins, card config)
                                                   ▼
                                          in-memory product store
                                          (15 seeded products today;
                                           MongoDB persistence planned)
```

The catalog is a **reference-data provider**: it sits upstream of the operational money-path services, which read product definitions but never write back.

## Key use cases

| Use case | API | Event |
|---|---|---|
| List products (filter type/status/currency) | `GET /api/v1/products` | — |
| Get one product | `GET /api/v1/products/{id}` | — |
| Create a product | `POST /api/v1/products` | — |
| Update a product | `PUT /api/v1/products/{id}` | — |
| Activate a product | `POST /api/v1/products/{id}/activate` | — |
| Deactivate a product | `POST /api/v1/products/{id}/deactivate` | — |
| Fees attached to one product | `GET /api/v1/products/{id}/fees` | — |
| Bank-wide fee schedule (filterable) | `GET /api/v1/fees` | — |

No domain events are emitted today (no Kafka/outbox) — the service is read-mostly reference data.

## Callers

- **admin-ui** — operators browse/maintain the product master and render the Fees pricing screen from `GET /api/v1/fees`.
- **account-service** — reads product definitions (type, currency, multi-currency config) at account opening.
- **interest / fx / card-issuance services** — read declared rates, FX margins, and card configuration.
- **customer-facing surfaces** — public product list (`isPublic=true`, status `ACTIVE`) for retail browsing.

## Dependencies

- **openbank-libs** — shared runtime plumbing (BuildInfo / `ServiceInfoResource`, DocsResource for Docs-as-Service, API-version filter).
- **PostgreSQL** — reactive Panache + reactive PG client for the app path, JDBC for Flyway (ADR-0009 / ADR-0105 P1); see [04 — Data](./04-data.md).
- **Keycloak** — pure OIDC resource server (`quarkus-oidc`, realm `openbank`): it validates bearer tokens against the realm JWKS and mints none, so it needs no client secret.
- **No** Kafka / Redis wiring in the code today.

## Business value

- **Single source of truth for products and pricing** — the price list lives in the catalog, not duplicated in the web tier; `GET /api/v1/fees` is the one place the UI reads fees.
- **Consistent product definitions** — account, interest, FX and card services all read the same product master, removing drift between "what we sell" and "what we run".
- **Versioned, transparent pricing** — every product carries `versionHistory` and `termsAndConditions` with effective dates, supporting consumer-transparency and audit needs (see [06 — Compliance](./06-compliance.md)).
