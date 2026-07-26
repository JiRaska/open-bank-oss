# API

REST kontrakt obsluhovaný na `/api/v1/journals` a `/api/v1/ledger/fx-revaluation`. OpenAPI dokument je přibalen v `src/main/resources/openapi.yaml` (`info.version: 1.1.0`) a vystaven na `/q/openapi` (Swagger UI na `/q/swagger-ui` v dev).

> **Poznámka ke kontraktu:** přibalený `openapi.yaml` aktuálně dokumentuje jen čtecí endpointy (`GET /journals`, `GET /journals/{journalId}`, `GET /journals/sub-ledger-balances`, `GET /journals/transaction/{transactionId}`). Endpointy `GET /journals/trial-balance`, `POST /journals`, `POST /journals/{journalId}/reverse` a `POST /ledger/fx-revaluation` v `LedgerResource` / `FxRevaluationResource` existují, ale **zatím nejsou v OpenAPI souboru** — uzavření této mezery je evidovaný follow-up (zdrojem pravdy níže jsou resource třídy).

## Verzování (ADR-0048)

- URL prefix `/api/v1` — `v{major}` == `openbank.api.version` (`"1"`) == major z `openapi.yaml:info.version`.
- **Release** verze (`version.txt` = `1.2.0`) je nezávislá osa od **API kontraktové** verze (`openapi.yaml:info.version` = `1.1.0`). Nesmí se násilně srovnávat.
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`.

## Autentizace a autorizace

Keycloak OIDC, RS256 bearer JWT. Žádný endpoint není `@PermitAll` — hlavní kniha je kniha záznamů (ADR-0018). Role z `libs.security.Roles`:

| Operace | Vyžadované role |
|---|---|
| Všechna čtení (list/get/trial-balance/sub-ledger/by-transaction) | `ROLE_API`, `ROLE_AUDITOR`, `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /journals` (zaúčtování) | `ROLE_OPERATOR` |
| `POST /journals/{id}/reverse` | `ROLE_OPERATOR` |
| `POST /ledger/fx-revaluation` | `ROLE_OPERATOR` |

Matice rolí je zamčena testem `LedgerSecurityContractTest`.

## Endpointy

### `GET /api/v1/journals` — seznam zápisů (cursor stránkování)

Query parametry: `fromDate` (výchozí `2020-01-01`), `toDate` (výchozí dnes), `limit` (výchozí 20), `cursor`.
Vrací `CursorPage<JournalEntryResponse>`: `{ data: [...], pagination: { nextCursor, hasMore } }`.

### `GET /api/v1/journals/{journalId}` — jeden zápis

`404` (ApiError), pokud nenalezeno.

### `GET /api/v1/journals/transaction/{transactionId}` — zápisy pro transakci

Vrací pole `JournalEntryResponse` (transakce může mít více zápisů, např. originál + storno).

### `GET /api/v1/journals/trial-balance` — předvaha

Query parametr: `asOf` (výchozí dnes). Vrací debet/kredit součty po účtech HK plus celkový příznak `balanced` (musí dát nulu). Slouží jako důkaz pro SOX/DORA a koncodenní rekonciliaci.

### `GET /api/v1/journals/sub-ledger-balances` — analytická evidence (ADR-0039 fáze B)

Query parametry: `asOf` (výchozí dnes), `subAccountId` (volitelný filtr). Agreguje POSTED řádky nesoucí `sub_account_id`, seskupené dle `(subAccountId, currency)`, s `totalDebit`, `totalCredit` a `net` (kredit − debet; deposit-control je kredit-normal). Tie-outuje deposit-control účet HK proti read-modelu po účtech (ČNB 563/1991 + 501/2002).

### `POST /api/v1/journals` — zaúčtuj vyvážený zápis

Tělo (`PostJournalRequest`):

```json
{
  "idempotencyKey": "txn-9f2c-post",
  "transactionId": "…uuid…",
  "entryDate": "2026-06-09",
  "valueDate": "2026-06-09",
  "description": "Vypořádání zákaznického převodu",
  "createdBy": "…uuid operátora…",
  "lines": [
    { "glAccountId": "…", "side": "DEBIT",  "amount": 100.00, "currencyCode": "CZK",
      "baseAmount": 100.00, "baseCurrencyCode": "CZK", "subAccountId": "…", "fxRate": null },
    { "glAccountId": "…", "side": "CREDIT", "amount": 100.00, "currencyCode": "CZK",
      "baseAmount": 100.00, "baseCurrencyCode": "CZK" }
  ]
}
```

- **Musí obsahovat ≥ 2 řádky a vyvažovat se v rámci každé bázové měny** — jinak `init` agregátu zápis odmítne (mapováno na chybu třídy `400`).
- `201 Created` s `Location: /api/v1/journals/{id}` a tělem `JournalEntryResponse`.

### `POST /api/v1/journals/{journalId}/reverse` — storno zaúčtovaného zápisu

Tělo (`ReverseJournalRequest`): `{ "reason": "...", "reversedBy": "…uuid…" }`. Vytvoří nový vyvážený zápis s otočenými stranami, provázaný přes `reversal_of`. Stornovat lze jen `POSTED` zápisy. Vrací `200` se storno `JournalEntryResponse`.

### `POST /api/v1/ledger/fx-revaluation` — denní FX revalvace (ADR-0046)

Query parametr: `date` (výchozí dnes, Europe/Prague). Znovu spustí denní mark-to-ČNB revalvaci pro daný obchodní den. **Idempotentní** — právě jeden zápis na den (`idempotencyKey = fx-reval-{date}`); opakování ve stejný den je no-op. Vrací `FxRevaluationResult` (`posted`, `journalId`, per-měnové `movements`).

## Idempotence

Zaúčtování je idempotentní přes **pole `idempotencyKey` v těle požadavku** (ne přes HTTP hlavičku). Klíč mapuje na právě jeden zápis přes tabulku `ledger_idempotency`; opakování se stejným klíčem vrátí původní zápis místo dvojího zaúčtování. To je money-path pojistka proti at-least-once retry upstreamu.

## Chybový model

`ApiError`: `{ "code": "...", "message": "..." }`. Mapování je centralizováno v `ExceptionMappers` a generický fallback je delegován na `openbank-libs` (ADR-0049 D4 — služba neregistruje catch-all `Exception` mapper). Typické stavy: `400` (nevyvážené / neplatné zaúčtování), `401`/`403` (auth), `404` (neznámý zápis), `409` (konflikt idempotency klíče).
