# Přehled

## Co služba dělá

`openbank-product-catalog` je **systém záznamu pro bankovní produkty a jejich ceny**. Vlastní:

- **Produktový master** — jeden záznam na produkt (např. `SAVINGS_STANDARD`, `CURRENT_PERSONAL`, `LOAN_PERSONAL_5Y`, `MORTGAGE_FIXED_20Y`, `CREDIT_CARD_CLASSIC`, `TERM_DEPOSIT_12M`, `OVERDRAFT_PERSONAL`, multi-měnový umbrella). Každý nese identitu (`code`, `name`, `type`, `currency`), životní cyklus `status` (DRAFT / ACTIVE / INACTIVE / DEPRECATED / ARCHIVED), ceny (`baseRate`, `fee`, `fees[]`), cílové segmenty, historii verzí a obchodní podmínky.
- **Konfigurační bloky podle typu** — `cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig` (sazby, FX marže, sítě/úrovně karet, výpovědní lhůta atd.).
- **Celobankovní sazebník poplatků** — všechny poplatky produktů zploštělé do jednoho filtrovatelného sazebníku (`FeeScheduleItem`), kde každý řádek nese identitu vlastnícího produktu a odvozený stabilní kód (např. `CURRENT_PERSONAL_FX_CONVERSION`). To je vlastní zdroj pravdy katalogu pro ceny, takže **admin UI nikdy nezadrátuje ceník napevno**.

## Co služba **NEDĚLÁ**

- Nezakládá ani nedrží účty — to je `openbank-account-service` (ten konzumuje definice produktů).
- Nepřesouvá peníze, neúčtuje zápisy ani nepočítá zůstatky — služby `ledger` / `transaction` / `balance`.
- Nenabíhá ani nevyplácí úroky — `interest-service` (katalog jen deklaruje sazby).
- Neprovádí FX směnu — `fx-service` (katalog jen deklaruje FX marže produktu).
- Neprovádí výkon cen/účtování — publikuje *sazebník*; účtování probíhá ve službách na peněžní cestě.
- Nevydává karty — `card-issuance-service` (katalog jen deklaruje konfiguraci karty: sítě, úrovně, limity).
- Není služba na peněžní cestě — neteče přes ni žádný kapitál.

## Pozice v doméně

```
   ┌────────────┐  GET/POST /products   ┌──────────────────────┐
   │  admin UI  │ ───────────────────►  │ product-catalog (8104)│
   └────────────┘  GET /fees            └─────────┬────────────┘
                                                   │ čte definice/ceny produktů
        account-service ─────────────────────────►│  (kód produktu, typ, poplatky)
        interest / fx / card-issuance ────────────►│  (sazby, FX marže, konfig karty)
                                                   ▼
                                          in-memory produktové úložiště
                                          (dnes 15 nasazených produktů;
                                           plánovaná perzistence v MongoDB)
```

Katalog je **poskytovatel referenčních dat**: sídlí proti proudu od provozních money-path služeb, které čtou definice produktů, ale nikdy nezapisují zpět.

## Klíčové use-casy

| Use-case | API | Událost |
|---|---|---|
| Výpis produktů (filtr type/status/currency) | `GET /api/v1/products` | — |
| Detail produktu | `GET /api/v1/products/{id}` | — |
| Vytvoření produktu | `POST /api/v1/products` | — |
| Úprava produktu | `PUT /api/v1/products/{id}` | — |
| Aktivace produktu | `POST /api/v1/products/{id}/activate` | — |
| Deaktivace produktu | `POST /api/v1/products/{id}/deactivate` | — |
| Poplatky jednoho produktu | `GET /api/v1/products/{id}/fees` | — |
| Celobankovní sazebník (filtrovatelný) | `GET /api/v1/fees` | — |

Dnes se nevydávají žádné doménové události (žádná Kafka/outbox) — služba je převážně čtecí referenční data.

## Volající

- **admin-ui** — operátoři procházejí/udržují produktový master a vykreslují obrazovku poplatků z `GET /api/v1/fees`.
- **account-service** — čte definice produktů (typ, měna, multi-měnová konfigurace) při zakládání účtu.
- **interest / fx / card-issuance** — čtou deklarované sazby, FX marže a konfiguraci karty.
- **zákaznické plochy** — veřejný výpis produktů (`isPublic=true`, status `ACTIVE`) pro retailové procházení.

## Závislosti

- **openbank-libs** — sdílené runtime instalatérství (BuildInfo / `ServiceInfoResource`, DocsResource pro Docs-as-Service, filtr API verze).
- **PostgreSQL** — reaktivní Panache + reaktivní PG klient pro aplikační cestu, JDBC pro Flyway (ADR-0009 / ADR-0105 P1); viz [04 — Data](./04-data.md).
- **Keycloak** — čistý OIDC resource server (`quarkus-oidc`, realm `openbank`): validuje bearer tokeny proti JWKS realmu a žádné nevydává, takže nepotřebuje client secret.
- **Žádné** zapojení Kafky / Redisu v kódu dnes.

## Obchodní hodnota

- **Jediný zdroj pravdy pro produkty a ceny** — ceník žije v katalogu, neduplikuje se ve webové vrstvě; `GET /api/v1/fees` je jediné místo, odkud UI čte poplatky.
- **Konzistentní definice produktů** — služby account, interest, fx i card čtou tentýž produktový master, čímž mizí drift mezi „co prodáváme" a „co provozujeme".
- **Verzované, transparentní ceny** — každý produkt nese `versionHistory` a `termsAndConditions` s daty účinnosti, což podporuje spotřebitelskou transparentnost a auditní potřeby (viz [06 — Compliance](./06-compliance.md)).
