# API & kontrakty

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (`info.version: 1.2.0`, OpenAPI 3.1). Major verze API kontraktu mapuje na URL prefix `/api/v1` (ADR-0048 — verze API kontraktu je nezávislá na release verzi služby `version.txt`).

## Základní cesta

- **Produkční base:** `http://openbank-interest-service:8125/api/v1` (in-cluster)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.path`, vždy zahrnuto)

> Pozn.: blok `servers:` v `openapi.yaml` uvádí lokální dev URL na portu 8119; **autoritativní app HTTP port je 8125** (`quarkus.http.port` v `application.yaml`), management na 8085.

## Autentizace & autorizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`). Role jsou vynucovány přes `@RolesAllowed`:

| Třída operace | Povolené role |
|---|---|
| Čtení (`GET` accrualy, souhrn, kapitalizace, sazby, odvody) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` (odvody navíc `ROLE_AUDITOR`) |
| Mutace (`POST` accrue, capitalize, rates, remittances; `DELETE` rate) | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |

## Idempotence

- v1 **nehlídá** mutace hlavičkou `Idempotency-Key` (do resources není zapojen žádný Redis idempotency store).
- **Sestavení odvodu je idempotentní konstrukcí**: existuje jedna dávka na `(year, month)` (DB unique constraint `uq_withholding_remittance_period` na `(period_year, period_month, authority)`). Opakované `POST /withholding/remittances` pro již sestavené období vrátí existující dávku a žádné řádky srážky znovu neoznačí.
- Denní accrualy jsou deduplikovány na datové vrstvě unikátním constraintem `(account_id, accrual_date, product_id)`.

## Endpointy

### Interest (tag `Interest`)

| Metoda | Cesta | Účel |
|---|---|---|
| `POST` | `/api/v1/interest/accrue` | Naběhnout úrok pro jeden účet (tělo `AccrualRequest`) |
| `POST` | `/api/v1/interest/accrue/all?date=` | Naběhnout úrok pro všechny účty (v1 vrací placeholder `{processed: 0}`) |
| `POST` | `/api/v1/interest/capitalize/{accountId}?productId=&toDate=` | Kapitalizovat naběhlý úrok; aplikuje srážku, připíše netto |
| `GET` | `/api/v1/interest/accruals` | Vypsat všechny accrualy |
| `GET` | `/api/v1/interest/accruals/{accountId}?from=&to=` | Accrualy pro účet |
| `GET` | `/api/v1/interest/accruals/{accountId}/summary?from=&to=` | Souhrn accrualů (default: minulý měsíc → dnes) |
| `GET` | `/api/v1/interest/capitalizations/{accountId}` | Historie kapitalizací |
| `POST` | `/api/v1/interest/rates` | Vytvořit konfiguraci sazby (tělo `InterestRateConfig`) |
| `GET` | `/api/v1/interest/rates?productId=` | Vypsat konfigurace sazeb |
| `GET` | `/api/v1/interest/rates/{id}` | Získat konfiguraci sazby podle id |
| `DELETE` | `/api/v1/interest/rates/{id}` | Deaktivovat konfiguraci sazby |

### Odvod srážkové daně (tag `Withholding remittance`)

| Metoda | Cesta | Účel |
|---|---|---|
| `POST` | `/api/v1/interest/withholding/remittances?year=&month=` | Sestavit (nebo vrátit) měsíční dávku; posune `RECORDED → REMITTED` |
| `GET` | `/api/v1/interest/withholding/remittances` | Vypsat sestavené dávky |
| `GET` | `/api/v1/interest/withholding/remittances/{year}/{month}` | Získat dávku za období (`404` pokud žádná) |

### Kapitalizace — příklad

```http
POST /api/v1/interest/capitalize/7f3e2a1b-...?productId=SAVINGS_STD&toDate=2026-05-31
Authorization: Bearer <token>
```

```http
200 OK
Content-Type: application/json

{
  "id": "c0ffee00-...",
  "accountId": "7f3e2a1b-...",
  "productId": "SAVINGS_STD",
  "periodFrom": "2026-05-01",
  "periodTo": "2026-05-31",
  "totalAccrued": 1234.567890,
  "grossAmount": 1234.5679,
  "taxAmount": 185.0000,
  "netAmount": 1049.5679,
  "capitalizedAmount": 1049.5679,
  "currency": "CZK",
  "ledgerEntryId": null,
  "createdAt": "2026-06-01T02:00:01Z"
}
```

Zákazníkovi se připisuje **netto** částka. Pro ne-CZK úrok se ve v1 nesráží (`treatment = DEFERRED_FX`), takže `net = gross` a `taxAmount = 0`.

### Rozpad srážkové daně (ADR-0033)

| Příjemce | Treatment | Sazba | Efekt |
|---|---|---|---|
| CZK, rezident fyzická osoba | `WITHHELD` | 15 % (§36) | připsáno netto, daň zaznamenána |
| CZK, nerezident, nespolupracující/bezsmluvní stát | `WITHHELD` | 35 % (§36/1/c) | připsáno netto |
| CZK, nerezident se smluvní sazbou | `WITHHELD` | smluvní sazba | připsáno netto |
| CZK, právnická osoba | `NOT_WITHHELD` | 0 | připsáno brutto (vstupuje do základu DPPO) |
| CZK, zákonné/smluvní osvobození v evidenci | `EXEMPT` | 0 | připsáno brutto, důvod zaznamenán |
| Ne-CZK úrok | `DEFERRED_FX` | 0 | připsáno brutto; srážka odložena |

Daňový základ a částka daně se zaokrouhlují **dolů na celé CZK** (daňový řád).

## Chybový model

Resources aktuálně překládají doménová selhání na generické tělo:

```json
{ "error": "No active rate config for product SAVINGS_STD" }
```

| HTTP | Kdy |
|---|---|
| 200 | úspěšné čtení / kapitalizace / deaktivace |
| 201 | vytvořena konfigurace sazby, accrual, sestaven odvod |
| 401 | chybějící / neplatný token |
| 403 | chybí role pro endpoint |
| 404 | konfigurace sazby / odvod nenalezen |
| 500 | doménové selhání (recovered na `{error: ...}`) — např. žádná aktivní sazba, žádné accrualy ke kapitalizaci |

> Jednotné RFC-9457 problem-detail tělo (`openbank-libs.api.ApiError`) je platformový směr; tato služba ve v1 používá jednodušší tvar `{error}`.

## Události

Topic: `openbank.interest.accrual.event` (JSON payload; partition key = `aggregate_id`; hlavičky `ce-id` / `ce-type` / `idempotency-key`).

| Typ události | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `interest.withholding.recorded.v1` | kapitalizace | `schemaVersion`, `capitalizationId`, `withholdingId`, `accountId`, `productId`, `periodFrom/To`, `currency`, `grossAmount`, `taxableBase`, `rate`, `taxAmount`, `netAmount`, `treatment`, `status` |
| `interest.withholding.remitted.v1` | sestavení odvodu | `schemaVersion`, `remittanceId`, `periodYear`, `periodMonth`, `authority`, `currency`, `totalTaxAmount`, `itemCount`, `dueDate`, `status` |

Události jsou **append-only** a nesou `schemaVersion`. Evoluce schématu je pouze aditivní.

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`). Major kontraktu == `openbank.api.version` == URL prefix (ADR-0048). Breaking změny ⇒ `/api/v2`.
- **Verze události v sufixu typu** (`...v1`) plus `schemaVersion` v payloadu. Pouze aditivní evoluce.
- **OpenAPI diff** v CI (`oasdiff`) klasifikuje bump kontraktu nezávisle na release služby.
