# API

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version` **0.3.0**). Všechny endpointy jsou pod `/api/v1` (URL major odpovídá `openbank.api.version`, ADR-0048). Swagger UI je na `/api/docs`.

## Autentizace a role

Keycloak OIDC (RS256 bearer). Resource jsou role-gated přes `@RolesAllowed`:

- **Čtení** (list, render, export, dotazy na close-run): `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_API`.
- **Mutace** (uzávěrka období, manuální spuštění close-run): `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API`.

Odchozí volání do služeb transaction / balance / account / party nesou samostatný **client-credentials** M2M token (`openbank-services`, `ROLE_OPERATOR`), přidaný `OidcClientRequestReactiveFilter`.

## Endpointy — Výpisy

### `POST /api/v1/statements/{accountId}/close`

Uzavře měsíc pro **každou kapsu** účtu. Přidělí další právní/elektronickou sekvenci per kapsa, zachytí počáteční/koncové zůstatky a spustí fail-closed rekonciliaci.

- Query parametry: `from` (datum, povinné), `to` (datum, povinné).
- **Idempotentní** na `(accountId, pocketCurrency, periodFrom, periodTo)` — opakované spuštění vrátí existující uzávěrku, nikdy ne novou sekvenci.
- `200` → pole `StatementPeriod` (jeden na kapsu).
- `409` → fail-closed nesoulad rekonciliace; **žádný výpis se nevydá** (tělo `{ "error": "..." }`).

### `GET /api/v1/statements/{accountId}`

Vypíše uchovávané záznamy uzávěrek pro účet. `200` → pole `StatementPeriod`.

### `GET /api/v1/statements/{accountId}/{currency}/{legalSequence}`

Vyrenderuje uzavřený výpis **na vyžádání**. Nic se neukládá; odpověď je vyrenderována deterministicky z uzavřeného období plus přehraných zaúčtovaných položek.

- Query parametr: `format` ∈ `CAMT_053` | `MT940` | `PDF` (výchozí `PDF`).
- `200` → vyrenderovaný výpis (`application/xml` pro camt.053, `text/plain` pro MT940/PDF — content type je nastaven z výstupu rendereru).
- `404` → žádný uzavřený výpis s touto sekvencí (tělo `{ "error": "..." }`).

### `GET /api/v1/statements/{accountId}/{currency}/export`

Ad-hoc, **bez sekvence informativní** export pro libovolný rozsah dat (právní/elektronická sekvence = 0). Stejné možnosti `format`.

- Query parametry: `from` (datum), `to` (datum), `format`.
- `200` → vyrenderovaný informativní export (nenese právní sekvenci).

## Endpointy — Close runs výpisů (provozní telemetrie, ADR-0069 D3)

### `GET /api/v1/statements/close-runs`
Nedávné plánované/manuální close runs, nejnovější první. Query `limit` (výchozí 20). `200` → pole `CloseRun`.

### `POST /api/v1/statements/close-runs`
Spustí manuální dohánějící průchod uzávěrkou nyní (operátorský retry). Provede plný self-healing průchod; selhání per-kapsa jsou izolována, zaznamenána a emitována jako `period.close_failed`. `202` → provedený `CloseRun`.

### `GET /api/v1/statements/close-runs/latest`
Nejnovější close run. `200` → `CloseRun`; `204` → kadence nikdy neběžela.

### `GET /api/v1/statements/close-runs/{runId}/failures`
Selhání per-kapsa zaznamenaná v rámci close runu. `200` → pole `CloseFailure`.

## Schémata

### `StatementPeriod` (jediný ukládaný artefakt výpisu)
`id`, `accountId`, `pocketCurrency` (ISO-4217, 3), `periodFrom`, `periodTo`, `legalSequenceNumber`, `electronicSequenceNumber`, `openingBalance`, `closingBalance`, `entryCount`, `status` ∈ `CLOSED` | `SUPERSEDED`, `supersedesSequence` (nullable), `closedAt`.

### `CloseRun`
`id`, `trigger` ∈ `SCHEDULED` | `MANUAL`, `status` ∈ `RUNNING` | `COMPLETED` | `COMPLETED_WITH_FAILURES`, `periodFrom`/`periodTo` (nullable), `accountsEnumerated`, `pocketsClosed`, `pocketsFailed`, `pocketsSkipped`, `startedAt`, `finishedAt` (nullable).

### `CloseFailure`
`id`, `runId`, `accountId`, `pocketCurrency`, `periodFrom`, `periodTo`, `reason` ∈ `RECONCILIATION` | `UPSTREAM` | `UNKNOWN`, `detail` (nullable), `failedAt`.

## Model chyb

| Status | Kdy | Tělo |
|---|---|---|
| `200` | úspěch | resource / pole |
| `202` | manuální close run přijat | `CloseRun` |
| `204` | dotaz na latest close run, kadence nikdy neběžela | (prázdné) |
| `404` | render: žádný uzavřený výpis s touto sekvencí | `{ "error": "..." }` |
| `409` | uzávěrka: fail-closed nesoulad rekonciliace | `{ "error": "..." }` |

`409` je nosný režim selhání: vypočtený koncový zůstatek (`opening ± zaúčtovaný čistý pohyb`) nesouhlasil s koncovým zůstatkem hlášeným balance-service, takže **žádný záznam období ani událost se neprodukuje** — samo-rozporný právní výpis se nikdy nevydá.

## Verzování

Dvě nezávislé osy (ADR-0048): **release** verze (`version.txt` = 0.3.0, vlastněná release-please) a verze **API kontraktu** (`openapi.yaml: info.version` = 0.3.0). URL major `/api/v1` se rovná `openbank.api.version`. Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`.

## Události

- **Out** — `account.statement.period.closed.v1` na Kafka topicu `openbank.statement.event` (emitováno transakčně přes outbox při každé čisté uzávěrce). Provozní kadence může navíc emitovat `period.close_failed` per selhanou kapsu.
- **In** — `openbank.accounts.account.created` (`AccountCreated`), konzumováno do lokální projekce `account_registry`.
