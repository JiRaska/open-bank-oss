# API

REST kontrakt je definován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1, `info.version` 1.1.0) a servírován z `TransactionResource`. Všechny cesty jsou pod `/api/v1` (`openbank.api.version = 1`, ADR-0048). `X-API-Version` / `X-Service-Version` a `/api/v1/info` servíruje `openbank-libs`.

> Poznámka: `servers` v `openapi.yaml` uvádí pro lokální vývoj port 8101, ale běžící aplikace se váže na **8102** (`application.yaml`). POST iniciační endpoint je implementován v `TransactionResource`, ale zatím není vyjmenován v `openapi.yaml` — kontrakt pro `POST /api/v1/transactions` berte jako zdokumentovaný zde, dokud nebude spec přegenerován (TBD).

## Endpointy

| Metoda | Cesta | Auth (role) | Účel |
|---|---|---|---|
| `GET` | `/api/v1/transactions?accountId&limit&cursor` | SERVICE, VIEWER, OPERATOR, ADMIN | Výpis transakcí účtu, cursor stránkování |
| `GET` | `/api/v1/transactions/search` | SERVICE, VIEWER, OPERATOR, ADMIN | Vyhledávání podle BIAN (IBAN/BBAN/reference/protistrana/částka/datum/…) |
| `GET` | `/api/v1/transactions/{transactionId}` | SERVICE, VIEWER, OPERATOR, ADMIN | Načtení jedné transakce podle id |
| `POST` | `/api/v1/transactions` | OPERATOR | Iniciace transakce (řídí platební ságu) |

Všechna čtení jsou autentizovaná — neexistuje **žádný `@PermitAll` endpoint** (K7 / ADR-0018): historie transakcí jsou finanční data zákazníka a vyhledávací endpoint dotazuje podle IBAN/částky/protistrany, takže je gatovaný na servisní volající plus viewery/operátory/adminy. Vynucení je zajištěno `TransactionSecurityContractTest`.

## Výpis — `GET /api/v1/transactions`

Query parametry: `accountId` (uuid, povinný), `limit` (default 20), `cursor` (neprůhledný, base64 posledního id).

Odpověď `200` — `TransactionPage`:

```json
{
  "data": [ { "...": "TransactionResponse" } ],
  "pagination": { "nextCursor": "…", "hasMore": true }
}
```

Cursor stránkování používá `libs.api.pagination.CursorEncoder`; služba načte `limit + 1` řádků pro výpočet `hasMore` a `nextCursor` vrací jen pokud existují další stránky.

## Vyhledávání — `GET /api/v1/transactions/search`

Volitelné filtry: `accountId`, `iban`, `bban`, `referenceNumber`, `endToEndId`, `counterparty`, `status` (`PENDING|COMPLETED|REVERSED|FAILED`), `type` (`CREDIT|DEBIT|REVERSAL|FEE`), `dateFrom`, `dateTo` (ISO datum), `amountMin`, `amountMax`, `channel`, `limit` (default 50, **omezeno na 1..200**), `offset` (default 0, omezeno ≥ 0).

Odpověď `200` — `TransactionSearchResult`:

```json
{ "data": [ … ], "count": 12, "limit": 50, "offset": 0 }
```

Neparsovatelné hodnoty `status`/`type` jsou ignorovány (považovány za žádný filtr), nikoli odmítnuty.

## Načtení — `GET /api/v1/transactions/{transactionId}`

Odpověď `200` — `TransactionResponse`; `404` (`ApiError`) když nenalezeno (mapováno z `TransactionNotFoundException`).

## Iniciace — `POST /api/v1/transactions`

Tělo požadavku (`InitiateTransactionRequest`):

```json
{
  "idempotencyKey": "string (povinný)",
  "type": "DEBIT|CREDIT|TRANSFER|FEE|INTEREST|REVERSAL|ADJUSTMENT",
  "sourceAccountId": "uuid | null",
  "targetAccountId": "uuid | null",
  "amount": 100.00,
  "currencyCode": "CZK",
  "baseCurrencyCode": "EUR | null",
  "description": "string | null",
  "valueDate": "2026-06-09"
}
```

Při úspěchu → `201 Created` s `Location: /api/v1/transactions/{id}` a tělem `TransactionResponse`. Volání spustí platební ságu synchronně, takže vrácený `status` je už terminální (`COMPLETED` nebo `FAILED`).

### TransactionResponse

| Pole | Typ | Poznámky |
|---|---|---|
| `id` | uuid | |
| `referenceNumber` | string | generované `TXN<epochMillis><rand>` |
| `type` | string | TransactionType |
| `sourceAccountId` / `targetAccountId` | uuid? | |
| `amount` | number | |
| `currencyCode` | string | ISO 4217 |
| `status` | string | TransactionStatus |
| `description` | string? | |
| `valueDate` / `bookingDate` | date | určeno `SettlementDateResolver` |
| `initiatedAt` / `completedAt` | date-time | `completedAt` null dokud není terminální |

## Idempotence

- Volající dodá `idempotencyKey` v iniciačním požadavku. Replay vrátí **existující** transakci (`findByIdempotencyKey`) — žádné duplicitní zaúčtování.
- Vynuceno v DB přes `uq_transactions_idempotency (idempotency_key, booking_date)` a `uq_payment_sagas_idempotency (idempotency_key)`.
- Downstream zaúčtování v ledgeru je samo idempotentní (klíč `saga-{id}-ledger`); kompenzační vrácení mají tag `compensation-{txId}`.

## Model chyb

Chyby následují sdílené `CommonExceptionMappers` z `openbank-libs`:

| Výjimka | HTTP | Tělo |
|---|---|---|
| `IllegalArgumentException` (malformovaný vstup) | `400` | `VALIDATION_ERROR` |
| `IllegalStateException` (porušený invariant, např. nekladná částka) | `422` | `BUSINESS_RULE_VIOLATION` |
| `TransactionNotFoundException` | `404` | `{ "error": "…" }` (lokální mapper) |
| `FxRateUnavailableException` | propagováno → 5xx | žádný FX kurz pro zúčtování v jiné měně |
| jakákoli jiná `Exception` | `500` | korelačně uvědomělý `GenericExceptionMapper` |

Doménové invarianty, které musí vyjít jako 422, záměrně vyhazují `IllegalStateException` (`check(...)`), nikdy `IllegalArgumentException`, aby šly přes sdílený mapper bez kolize s lokálním (ADR-0049 D4).

## Verzování

- **Osa API kontraktu:** `openapi.yaml:info.version` (1.1.0); její major == URL `/api/v{N}` == `openbank.api.version` (1). Změna API klasifikuje svůj bump z OpenAPI diffu (ADR-0048).
- **Osa release:** `version.txt` (1.2.1), vlastní release-please — nezávislá na API ose.
