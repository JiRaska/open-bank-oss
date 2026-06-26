# Přehled

## Co služba dělá

`openbank-fx-service` je **kurzovní lístek a konverzní engine** platformy OpenBank. Drží:

- **FxRate** — kótovaný měnový pár (`baseCurrency`/`quoteCurrency`) s `bidRate`/`askRate` (a odvozeným `midRate`/`spread`), typem kurzu `rateType` (SPOT / FORWARD / INDICATIVE / INTERBANK), zdrojem `source` (ECB / REUTERS / BLOOMBERG / INTERNAL / CNB) a oknem platnosti (`validFrom`/`validTo`).
- **FxConversion** — provedený požadavek na konverzi: klient (party), volitelný účet, z/do měny, částky v minor units, použitý kurz `appliedRate` a `rateId` zafixovaný v okamžiku provedení, poplatek a stav životního cyklu `status` (PENDING / SETTLED / FAILED / REVERSED).
- **Kurz devizového trhu ČNB** — denní fixing stahovaný z feedu ČNB, ukládaný jako kurzy `source = CNB`, `rateType = INDICATIVE` kótované v CZK (ADR-0046).

Každá konverze je **synchronně prověřena** proti sankčním seznamům dříve, než smí být vypořádána (ADR-0032). Čistý klient se vypořádá (SETTLED); sankční zásah konverzi zamítne (FAILED) a otevře CRITICAL AML případ; potenciální zásah pod prahem nebo výpadek screening služby drží konverzi ve stavu PENDING k lidskému přezkoumání (fail-closed — nikdy nevypořádáno bez prověrky).

## Co služba **NEDĚLÁ**

- ❌ Nepřevádí peníze na účtech a neúčtuje do ledgeru — spočítá a zaeviduje konverzi; vypořádání účtují ledger/transaction/balance služby.
- ❌ Nepočítá zůstatky — to dělá `balance-service`.
- ❌ Nevlastní sankční seznamy ani backend AML případů — *volá* `sanctions-service` (screen) a `aml-service` (otevření případu).
- ❌ Není tržní datový terminál — drží malou sadu kurzů (interní seed + ČNB fixing pro konfigurované měny), ne plný tick feed.
- ❌ Neiniciuje platby — platební služby ji volají pro kurz nebo konverzi.

## Pozice v doméně

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
   ┌──────────────────┐  denní fixing feed           │  PostgreSQL  │
   │   ČNB feed (ext) │ ─────────────────────────►   │ (openbank_fx)│
   └──────────────────┘                              └──────────────┘
```

## Klíčové use-casy

| Use-case | API | Událost |
|---|---|---|
| Výpis všech aktuálních FX kurzů | `GET /api/v1/fx/rates` | — |
| Kurz pro pár (`?source=CNB` pro fixing ČNB) | `GET /api/v1/fx/rates/{base}/{quote}` | — |
| Provedení měnové konverze (prověřené) | `POST /api/v1/fx/convert` | `FxConversionExecuted` → `openbank.fx.conversion.completed` |
| Načtení konverze podle id | `GET /api/v1/fx/conversions/{id}` | — |
| Ingest ČNB fixingu pro den (ops/backfill) | `POST /api/v1/fx/cnb/ingest` | — |
| Načtení posledního ČNB fixingu pro měnu | `GET /api/v1/fx/cnb/rates/{base}` | — |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři čtou kurzy, spouští konverze/backfill.
- **platební / transakční služby** — žádají kurz (`ROLE_PAYMENTS`) nebo konverzi před/při vypořádání.
- **operátoři** — ingest/backfill ČNB fixingu (`ROLE_OPERATOR`/`ROLE_ADMIN`).

## Závislosti

- **PostgreSQL** (databáze `openbank_fx`, logické schéma `fx_schema`) — kurzy, konverze, outbox.
- **Kafka** — outbox publish do `openbank.fx.conversion.completed`.
- **Redis (Valkey)** — klient nakonfigurován (idempotence konverzí je vynucena přes DB unique key).
- **sanctions-service** — synchronní prověrka konvertujícího klienta (gate ADR-0032).
- **aml-service** — otevření CRITICAL/HIGH/MEDIUM AML případu při zásahu / přezkumu / nedostupnosti screeningu.
- **ČNB feed** (externí, `https://www.cnb.cz/...denni_kurz.txt`) — denní fixing (ADR-0046).
- **Keycloak** — OIDC autentizace.
- **openbank-libs** — sdílené web/security/build plumbing, DocsResource.

## Přínos pro byznys

- **Jeden kurzovní lístek** — interní kurzy plus oficiální fixing ČNB na jednom dotazovatelném místě, s `rateId`/časovou značkou zafixovanou na každé konverzi pro auditovatelnost a obranu sporů.
- **Compliance by construction** — žádná konverze se nevypořádá bez průchodu synchronní sankční bránou; zásahy a nejisté případy padají uzavřeně a vytvoří auditovatelný AML případ.
- **Šíření událostí** — vypořádané konverze jsou publikovány přes transakční outbox, takže navazující služby mají eventually-consistent pohled.
