# Architecture

## C4 — System Context

```mermaid
graph LR
  admin[admin-ui]
  acc[account-service]
  intr[interest-service]
  fx[fx-service]
  card[card-issuance-service]

  pc[(product-catalog<br/>:8104)]:::svc
  store[(PostgreSQL<br/>products JSONB + indexed columns)]

  admin -- "GET/POST/PUT /products<br/>GET /fees" --> pc
  acc -. "read product defs" .-> pc
  intr -. "read declared rates" .-> pc
  fx -. "read FX margins" .-> pc
  card -. "read card config" .-> pc

  pc --> store

  classDef svc fill:#dbeafe,stroke:#2563eb
```

The catalog is a **reference-data provider**. It has no downstream calls and no money-path
involvement. Accepted v2 changes create durable outbox records; no broker adapter is enabled yet.

## C4 — Container (internal structure)

```mermaid
graph TB
  subgraph "openbank-product-catalog (Quarkus 3.33, JDK 25)"
    direction TB
    rest[REST adapters<br/>v1 banking + v2 generic catalog]
    app[Application<br/>legacy CRUD + governed publication]
    dom[Domain<br/>bank Product + framework-free catalog kernel]
    port[Outbound ports<br/>legacy + generic repositories]
    store[Persistence adapter<br/>Reactive Panache + PostgreSQL<br/>Flyway schema]
  end

  rest --> app
  app --> dom
  app --> port
  port --> store
```

## Hexagonal layers (ADR-0002)

The package structure reflects **ports-and-adapters**:

```
com.openbank.productcatalog/
├── domain/                      ◄── core — no framework dependencies
│   └── Product.kt               Product aggregate + value objects:
│                                  Fee, InterestTier, CardConfig,
│                                  MultiCurrencyConfig, OverdraftConfig,
│                                  TermDepositConfig, SavingsConfig,
│                                  TermsAndConditions, ProductVersion
│                                  + enums (ProductType, ProductStatus, …)
│
├── application/                 ◄── use-case orchestration
│   └── ProductCatalogService.kt ProductCatalogService (CRUD + seed +
│                                  fee-schedule flattening),
│                                  ProductRequest (DTO ↔ domain),
│                                  FeeScheduleItem (flattened fee line)
│
└── infrastructure/
    ├── persistence/             ◄── outbound adapter (Reactive Panache/PostgreSQL)
    └── rest/                    ◄── inbound adapters (JAX-RS)
        ├── ProductCatalogResource   /api/v1/products
        └── FeesResource             /api/v1/fees
```

`ProductRepository` is the outbound persistence port. Native-image reflection registration lives in infrastructure, keeping the domain free of framework imports (ADR-0002).

## Domain model

The aggregate root is **`Product`** (identity `id`/`code`, `name`, `type`, `currency`, lifecycle `status`, pricing `baseRate`/`fee`/`fees[]`). Optional per-type configuration blocks attach rich behaviour:

| Block | Used by | Carries |
|---|---|---|
| `cardConfig` | CURRENT / CREDIT_CARD | networks, tiers, min/max cards, virtual/contactless, monthly fee |
| `multiCurrencyConfig` | multi-currency products | supported currencies, default currency, FX buy/sell margins |
| `overdraftConfig` | CURRENT / OVERDRAFT | arranged/unarranged limits, rates, grace, daily fee |
| `termDepositConfig` | TERM_DEPOSIT | term months, payout frequency, early-withdrawal penalty |
| `savingsConfig` | SAVINGS | tiered interest, withdrawal notice, free withdrawals, bonus rate |

`versionHistory[]` is legacy informational data, not immutable audit evidence. `termsAndConditions[]` carries effective-dated references. ADR-0257 introduces authoritative immutable revisions in v2.

## Fee schedule flattening

`ProductCatalogService.listFeeSchedule()` flattens every product's `fees[]` into a single bank-wide schedule. Each `FeeScheduleItem` carries:

- a stable composite id `"<productId>:<feeId>"`,
- a derived display code `"<PRODUCT_CODE>_<FEE_SLUG>"` (e.g. `CURRENT_PERSONAL_FX_CONVERSION`) that changes with fee metadata,
- the owning product identity (`productId`, `productCode`, `productName`) and its `status`, plus `updatedAt`.

This is what `GET /api/v1/fees` serves, so the admin UI renders pricing without re-fetching each product and never hardcodes a price list.

## Generic v2 aggregate boundary

`ProductSpecification` owns the canonical UUID, immutable code and exact `SchemaRef`.
`ProductOffering` adds market context. `ProductRevision` owns all localized content, schema-governed
attributes, exact decimal prices and effective dates. DRAFT is mutable behind a strong ETag;
PUBLISHED and SUPERSEDED snapshots are database-enforced immutable. Publication requires a checker
different from the stored maker.

## Events / outbox

Specification, offering, draft, update and publication changes persist the domain row, append-only
audit evidence and a `CatalogChangeEvent` v1 envelope atomically. A failed outbox insert rolls the
whole transaction back. The outbox is intentionally transport-neutral until a delivery adapter ships.
