# API

The REST contract is published at `/q/openapi` and browsable via Swagger UI at `/api/docs`. The committed contract is [`openapi.yaml`](../openapi.yaml) (`info.version 1.1.0`, OpenAPI 3.1.0). All paths are under `/api/v1` (ADR-0048 — the URL major matches `openbank.api.version`).

> **Contract note:** the committed `openapi.yaml` currently documents only the four core FX endpoints. The `?source=CNB` query parameter on the rate lookup and the two `/api/v1/fx/cnb/...` ingestion endpoints exist in the resource classes (`FxResource`, `CnbResource`) but are **not yet reflected in `openapi.yaml`** — closing that drift is a follow-up. The endpoints below are documented from the actual resource code.

## Endpoints

### FX rates & conversion (`FxResource`)

| Method | Path | Roles | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/fx/rates` | VIEWER, OPERATOR, ADMIN, PAYMENTS | List all current FX rates |
| `GET` | `/api/v1/fx/rates/{base}/{quote}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Get the latest SPOT rate for a pair; `?source=CNB` returns the central-bank fixing |
| `POST` | `/api/v1/fx/convert` | OPERATOR, ADMIN, PAYMENTS | Execute a screened conversion (idempotent) |
| `GET` | `/api/v1/fx/conversions/{id}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Get a conversion by id |

### ČNB fixing — ops/backfill (`CnbResource`)

| Method | Path | Roles | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/fx/cnb/ingest` | OPERATOR, ADMIN | Ingest the ČNB fixing for `?date=YYYY-MM-DD` (idempotent; omit for latest) |
| `GET` | `/api/v1/fx/cnb/rates/{base}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Latest ingested ČNB fixing for `{base}/CZK` |

Roles are enforced with Quarkus `@RolesAllowed` on bearer tokens issued by Keycloak.

## Convert request / response

**`POST /api/v1/fx/convert`** — header `Idempotency-Key` is **required** (the resource rejects a blank key).

Request body (`ConvertRequest`):

```json
{
  "partyId": "uuid",
  "accountId": "uuid|null",
  "partyName": "ACME s.r.o.",
  "fromCurrency": "EUR",
  "toCurrency": "CZK",
  "fromAmountMinorUnits": 100000
}
```

`partyName` is screened synchronously against the sanctions lists (ADR-0032). The conversion is computed at the latest valid **SPOT** rate's `askRate`; a fixed **0.5% fee** is applied (`feeMinorUnits`), `HALF_UP` rounded to whole minor units.

Response — `201 Created`, `Location: /api/v1/fx/conversions/{id}` (`ConversionResponse`):

```json
{
  "id": "uuid",
  "fromCurrency": "EUR",
  "toCurrency": "CZK",
  "fromAmount": 100000,
  "toAmount": 2515000,
  "appliedRate": 25.15,
  "status": "SETTLED",
  "convertedAt": "2026-06-09T14:41:00Z"
}
```

`status` ∈ `PENDING | SETTLED | FAILED | REVERSED`:

| Status | When | Side effect |
|---|---|---|
| `SETTLED` | screening CLEAR | `FxConversionExecuted` emitted |
| `PENDING` | screening REVIEW (potential hit ≤ 0.85) or screening service unavailable | HIGH / MEDIUM AML case opened; held for human review |
| `FAILED` | screening BLOCK (HIT / ESCALATED / potential hit > 0.85) | CRITICAL AML case opened |
| `REVERSED` | reserved (no reversal endpoint yet) | — |

## Idempotency

`POST /convert` requires `Idempotency-Key`. On replay, `FxService` looks the conversion up by key (`fx_conversions.idempotency_key` is `UNIQUE`) and returns the stored result — the same conversion is never executed twice.

## Versioning

- URL major: `/api/v1`. OpenAPI `info.version` is the API-contract axis (ADR-0048), independent of the release `version.txt`.
- `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by `openbank-libs`.

## Error model

Errors carry a small JSON body. The OpenAPI `ApiError` schema is `{ code, message }`; the current resource code returns `{ "error": "<message>" }` on not-found (`404`). Common cases:

| Status | Cause |
|---|---|
| `400` | blank `Idempotency-Key`; invalid request body |
| `401` / `403` | missing/invalid token; role not permitted |
| `404` | rate not found for pair; conversion id unknown; no ČNB rate for `{base}/CZK` |
| `4xx/5xx` | no valid SPOT rate available, or the rate is expired (`require`/`error` raised in the use-case) |
