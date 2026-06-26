# Přehled

## Co služba dělá

`openbank-statement-service` je **autorita pro výpisy z účtu** pro vícecurrencyový běžný účet OpenBank (ADR-0035). Jednotkou práce je **výpis za jednu kapsu** — jeden IBAN, jedna měna — každý s vlastní nezávislou právní sekvencí. Drží:

- **StatementPeriod** — uchovávaný záznam uzávěrky období: právní/elektronické pořadové číslo, kotvy počátečního/koncového zůstatku, počet položek, stav (CLOSED / SUPERSEDED), `closedAt`. Toto je **jediný** ukládaný artefakt výpisu.
- **StatementModel** — kanonický, neměnný in-memory agregát pro jednu kapsu za jedno období, ze kterého je každý vyrenderovaný formát *čistou projekcí*.
- **CloseRun / CloseFailure** — provozní telemetrie plánované/manuální kadence uzávěrek (ADR-0069 D3): kolik kapes se uzavřelo, selhalo nebo bylo přeskočeno a proč.
- **AccountRegistry** — read-only lokální projekce účtů (sestavená z event streamu account-service) sloužící k enumeraci účtů pro plánovanou měsíční uzávěrku.

Tři renderery (camt.053.001.08, MT940, PDF) produkují **deterministické, bajt po bajtu identické projekce na vyžádání** a zahazují je — vyrenderované soubory se nikdy neskladují. To je legální dle PSD2 čl. 58(2) ("poskytnuto *nebo zpřístupněno* … alespoň měsíčně, reprodukovatelné beze změny"): my *zpřístupňujeme*, soubory neposíláme.

## Co služba **NEdělá**

- ❌ Nepočítá ani nevlastní zůstatky — autoritativní koncový zůstatek pochází z `openbank-balance-service`; statement-service proti němu rekonciliuje fail-closed.
- ❌ Nevlastní transakce — zaúčtované položky se přehrávají z `openbank-transaction-service`.
- ❌ Neukládá vyrenderované soubory — perzistuje se pouze malý záznam `StatementPeriod`; camt.053 / MT940 / PDF se renderují na vyžádání.
- ❌ Nenetuje kapsy — každá měnová kapsa má vlastní výpis a sekvenci; konsolidované PDF nese pouze *informativní* součet v referenční měně (ADR-0024).
- ❌ Neprodukuje **roční výkaz poplatků dle PAD čl. 5** — to je *push* povinnost vlastněná doménou poplatků/billingu.
- ❌ Nehýbe penězi — není to money-path služba.

## Pozice v doméně

```
   ┌────────────────────────┐  AccountCreated   ┌──────────────────────┐
   │     account-service     │ ───────────────► │ statement-service    │
   └────────────────────────┘   (Kafka)         │  AccountRegistry     │
                                                 │  (enumerace)         │
   ┌────────────────────────┐  REST (M2M čtení) │                      │
   │  transaction-service    │ ◄─────────────── │  uzávěrka období +   │
   │  balance-service        │ ◄─────────────── │  render-na-vyžádání  │
   │  account / party        │ ◄─────────────── │                      │
   └────────────────────────┘                   └─────────┬────────────┘
                                                           │ outbox → Kafka
        admin UI / customer app                            ▼
        GET render (camt.053 / MT940 / PDF) ◄──────  openbank.statement.event
                                                  ( account.statement.period.closed.v1 )
                                                           │
                                                  PostgreSQL (openbank_statement)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Uzavřít měsíc pro každou kapsu účtu | `POST /api/v1/statements/{accountId}/close` | `account.statement.period.closed.v1` |
| Vypsat uchovávané záznamy uzávěrek | `GET /api/v1/statements/{accountId}` | — |
| Vyrenderovat uzavřený výpis na vyžádání | `GET /api/v1/statements/{accountId}/{currency}/{legalSequence}` | — |
| Ad-hoc informativní export (bez sekvence) | `GET /api/v1/statements/{accountId}/{currency}/export` | — |
| Prohlížet / spustit kadenci uzávěrek | `GET`/`POST /api/v1/statements/close-runs` | `period.close_failed` (při selhání kapsy) |
| Plánovaná měsíční uzávěrka (cron, self-healing) | — (cron `0 30 2 1 * ?`) | `account.statement.period.closed.v1` |

## Kdo volá

- **admin-ui / customer app** (přes Keycloak token) — render/výpis výpisů, prohlížení close runs.
- **operátoři / compliance** — spuštění manuálního dohánějícího běhu uzávěrek, prohlížení selhání.
- **scheduler (interní)** — spouští měsíční uzávěrku období.

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_statement`)
- **Kafka** (`openbank-kafka`) — out: `openbank.statement.event`; in: `openbank.accounts.account.created`
- **balance-service** (REST, M2M) — autoritativní koncový zůstatek pro rekonciliaci
- **transaction-service** (REST, M2M) — přehrání zaúčtovaných položek
- **account-service / party-service** (REST, M2M) — informace o kapsovém účtu, jméno majitele
- **Keycloak** — autentizace (příchozí bearer + odchozí client-credentials)
- **openbank-libs** — sdílená runtime infrastruktura (BuildInfo, DocsResource, konvence outboxu)

## Obchodní hodnota

- **Výpisy v regulatorní kvalitě** — per-kapsa camt.053 / MT940 / PDF s monotónními právními sekvencemi, reprodukovatelné bajt po bajtu (PSD2 čl. 58(2), ČNB).
- **Fail-closed integrita** — uzávěrka období, jejíž vypočtený koncový zůstatek nesouhlasí s balance-service, selže (HTTP 409); samo-rozporný právní dokument se nikdy nevydá.
- **Ukládej-záznam-ne-soubor** — uchovává se jen drobný záznam uzávěrky (10 let), rendery jsou deterministické projekce; minimální úložiště, maximální reprodukovatelnost.
- **Self-healing kadence** — měsíční uzávěrka automaticky dohání zmeškané měsíce a zaznamenává každý běh a selhání pro operátory (ADR-0069 D3).
