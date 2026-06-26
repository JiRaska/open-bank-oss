# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui]
  acc[account-service]
  intr[interest-service]
  fx[fx-service]
  card[card-issuance-service]

  pc[(product-catalog<br/>:8104)]:::svc
  store[(in-memory úložiště<br/>15 nasazených produktů)]

  admin -- "GET/POST/PUT /products<br/>GET /fees" --> pc
  acc -. "čte definice produktů" .-> pc
  intr -. "čte deklarované sazby" .-> pc
  fx -. "čte FX marže" .-> pc
  card -. "čte konfig karty" .-> pc

  pc --> store

  classDef svc fill:#dbeafe,stroke:#2563eb
```

Katalog je **poskytovatel referenčních dat**. Nemá downstream volání, žádného brokera ani účast na peněžní cestě — volající čtou definice produktů a sazebník.

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-product-catalog (Quarkus 3.33, JDK 25)"
    direction TB
    rest[REST adaptéry<br/>ProductCatalogResource<br/>FeesResource]
    app[Aplikace<br/>ProductCatalogService<br/>ProductRequest / FeeScheduleItem]
    dom[Doména<br/>Product + konfig value objekty<br/>Fee / InterestTier / *Config]
    store[Perzistence<br/>ConcurrentHashMap úložiště<br/>naseedováno při startu]
  end

  rest --> app
  app --> dom
  app --> store
```

## Hexagonální vrstvy (ADR-0002)

Struktura balíčků odráží **ports-and-adapters**:

```
com.openbank.productcatalog/
├── domain/                      ◄── jádro — bez framework závislostí
│   └── Product.kt               Agregát Product + value objekty:
│                                  Fee, InterestTier, CardConfig,
│                                  MultiCurrencyConfig, OverdraftConfig,
│                                  TermDepositConfig, SavingsConfig,
│                                  TermsAndConditions, ProductVersion
│                                  + enumy (ProductType, ProductStatus, …)
│
├── application/                 ◄── orchestrace use-casů
│   └── ProductCatalogService.kt ProductCatalogService (CRUD + seed +
│                                  zploštění sazebníku),
│                                  ProductRequest (DTO ↔ doména),
│                                  FeeScheduleItem (zploštělý řádek poplatku)
│
└── infrastructure/rest/         ◄── vstupní adaptéry (JAX-RS)
    ├── ProductCatalogResource   /api/v1/products
    └── FeesResource             /api/v1/fees
```

> Poznámka k aktuální zralosti: aplikační služba drží úložiště přímo (`ConcurrentHashMap`), nikoli za výstupním repository **portem**. Čisté oddělení doména→port→adaptér pro perzistenci je sledovaný follow-up, který přijde s DB úložištěm (viz [04 — Data](./04-data.md)). Samotná doménová vrstva je bez frameworků.

## Doménový model

Kořen agregátu je **`Product`** (identita `id`/`code`, `name`, `type`, `currency`, životní cyklus `status`, ceny `baseRate`/`fee`/`fees[]`). Volitelné konfigurační bloky podle typu připojují bohaté chování:

| Blok | Používá | Nese |
|---|---|---|
| `cardConfig` | CURRENT / CREDIT_CARD | sítě, úrovně, min/max karet, virtuální/bezkontaktní, měsíční poplatek |
| `multiCurrencyConfig` | multi-měnové produkty | podporované měny, výchozí měna, FX nákupní/prodejní marže |
| `overdraftConfig` | CURRENT / OVERDRAFT | povolené/nepovolené limity, sazby, odklad, denní poplatek |
| `termDepositConfig` | TERM_DEPOSIT | délka v měsících, frekvence výplaty, sankce za předčasný výběr |
| `savingsConfig` | SAVINGS | úroková pásma, výpovědní lhůta, počet výběrů zdarma, bonusová sazba |

Auditní/transparentní atributy: `versionHistory[]` (datem účinnosti opatřené poznámky k verzím) a `termsAndConditions[]` (verzované URL OP s daty účinnosti).

## Zploštění sazebníku

`ProductCatalogService.listFeeSchedule()` zploští `fees[]` všech produktů do jednoho celobankovního sazebníku. Každý `FeeScheduleItem` nese:

- stabilní složené id `"<productId>:<feeId>"`,
- odvozený stabilní kód `"<PRODUCT_CODE>_<FEE_SLUG>"` (např. `CURRENT_PERSONAL_FX_CONVERSION`),
- identitu vlastnícího produktu (`productId`, `productCode`, `productName`) a jeho `status`, plus `updatedAt`.

Toto poskytuje `GET /api/v1/fees`, takže admin UI vykreslí ceny bez opětovného načítání každého produktu a nikdy nezadrátuje ceník napevno.

## Události / outbox

**Žádné.** Služba neprovozuje outbox dispatcher a nepublikuje žádné Kafka události. Změny stavu (create/update/activate/deactivate) mutují pouze in-memory úložiště. Pokud by se události o změně produktu staly downstream požadavkem, byly by přidány za outbox podle platformního vzoru použitého v `account-service`.
