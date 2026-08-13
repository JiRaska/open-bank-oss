# Overview

## What the service does

`openbank-product-catalog` is the **system of record for governed product definitions and pricing**.
It serves an industry-neutral v2 catalog while preserving the v1 banking contract. It owns:

- **Generic catalog kernel** — immutable specifications, market-specific offerings, localized and
  effective-dated revisions, exact decimal price components, eligibility, relationships and document codes.
- **Trusted industry packs** — closed JSON Schema 2020-12 profiles. The initial banking-deposit and
  term-life insurance packs prove that industry attributes do not leak into the kernel.

- **Product master** — one record per product (e.g. `SAVINGS_STANDARD`, `CURRENT_PERSONAL`, `LOAN_PERSONAL_5Y`, `MORTGAGE_FIXED_20Y`, `CREDIT_CARD_CLASSIC`, `TERM_DEPOSIT_12M`, `OVERDRAFT_PERSONAL`, multi-currency umbrella). Each carries identity (`code`, `name`, `type`, `currency`), lifecycle `status` (DRAFT / ACTIVE / INACTIVE / DEPRECATED / ARCHIVED), pricing (`baseRate`, `fee`, `fees[]`), eligibility segments, version history and terms-and-conditions.
- **Per-type configuration blocks** — `cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig` (rates, FX margins, card networks/tiers, withdrawal notice, etc.).
- **Bank-wide fee schedule** — every product's fees flattened into one filterable schedule (`FeeScheduleItem`), each line carrying its owning product identity and a derived display code (e.g. `CURRENT_PERSONAL_FX_CONVERSION`). The display code changes when the fee name changes; only the composite id is identity. This is the catalog's own source of truth for pricing so the **admin UI never hardcodes a price list**.

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
                                          PostgreSQL product store
                                          (15 banking examples seeded
                                           into an empty database)
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
| Validate attributes against an exact product type | `POST /api/v2/product-types/{id}/versions/{version}/validate` | — |
| Author specification, offering and draft revision | `/api/v2/specifications`, `/offerings`, `/revisions` | durable audit + outbox |
| Publish with an independent checker | `POST /api/v2/offerings/{id}/revisions/{revisionId}/publish` | `catalog.revision_published` v1 |
| Resolve one effective published offering | `GET /api/v2/products/{offeringId}` | — |
| Pull committed catalog changes | `GET /api/v2/events?after={cursor}` | ordered durable outbox |

Every accepted v2 change records append-only audit evidence and a versioned outbox event in the
same PostgreSQL transaction. Standalone consumers can poll an opaque durable cursor; Kafka is optional.

## Callers

- **admin-ui** — Product Studio authors schema-governed v2 revisions; the legacy screen and Fees remain on v1.
- **account-service** — reads product definitions (type, currency, multi-currency config) at account opening.
- **interest / fx / card-issuance services** — read declared rates, FX margins, and card configuration.
- **customer-facing surfaces** — no dedicated public projection exists yet; they must not expose the operator list directly.

## Dependencies

- **openbank-libs** — shared runtime plumbing (BuildInfo / `ServiceInfoResource`, DocsResource for Docs-as-Service, API-version filter).
- **PostgreSQL** — reactive Panache + reactive PG client for the app path, JDBC for Flyway (ADR-0009 / ADR-0105 P1); see [04 — Data](./04-data.md).

The catalog also ships independently as an attested OCI image, a hardened Helm chart, and a Docker
Compose quickstart. Standalone mode requires only PostgreSQL plus a standard OIDC issuer and enables
no industry pack unless the operator opts in; see `standalone/README.md` in the service module.
- **Keycloak** — pure OIDC resource server (`quarkus-oidc`, realm `openbank`): it validates bearer tokens against the realm JWKS and mints none, so it needs no client secret.
- **No** Kafka / Redis runtime dependency today; the transactional outbox is delivery-neutral.

## Business value

- **Single source of truth for products and pricing** — the price list lives in the catalog, not duplicated in the web tier; `GET /api/v1/fees` is the one place the UI reads fees.
- **Consistent product definitions** — account, interest, FX and card services all read the same product master, removing drift between "what we sell" and "what we run".
- **Explicit product information** — products carry effective-dated terms and legacy version notes. These support operator context but are not an immutable audit trail; ADR-0257's revisions/publication evidence will provide that guarantee.
