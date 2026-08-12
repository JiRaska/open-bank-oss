# API & contracts

## Base path

- **In-cluster bases:** `http://openbank-product-catalog:8104/api/v1` and `/api/v2`
- **Local dev:** `http://localhost:8104/api/v1` and `/api/v2`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8085/q/openapi) on the management port (source of truth: `openapi.yaml`, `info.version` 2.0.0, OpenAPI 3.1.0)
- **Swagger UI:** `http://localhost:8085/api/docs` (configured via `quarkus.swagger-ui.path`, `always-include: true`)

The newest served major of `openapi.yaml:info.version` equals `openbank.api.version` and `/api/v2`.
The same contract preserves `/api/v1`; the response filter reports the major of the actual request
path. The release version (`version.txt`) is a separate axis ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).

## Authentication

The service is an OIDC resource server. Reads require an authenticated bearer token; create/update/lifecycle mutations require `ROLE_OPERATOR` or `ROLE_ADMIN`. `@Authorize` adds the policy action, but OPA is advisory until the bank deployment enables an enforcing profile. Browser traffic still goes through the authenticated BFF (ADR-0056).

CORS origins are restricted to the configured admin UI origins and expose `ETag`; `If-Match` is allowed for optimistic writes.

## Idempotency

No `Idempotency-Key` mechanism is implemented. Create is guarded by unique codes. v1 keeps its
optional legacy revision and 409 behavior for compatibility. v2 draft updates and publication require
a strong numeric `If-Match`: missing is 428 and stale/concurrent is 412.

## Endpoints

### Products

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| GET | `/api/v1/products` | List products; optional `?type=&status=&currency=` filters | 200 array | — |
| GET | `/api/v1/products/{id}` | Get one product | 200 | 404 |
| POST | `/api/v1/products` | Create a product (`ProductRequest`) | 201 + ETag | 400 validation, 409 duplicate code |
| PUT | `/api/v1/products/{id}` | Update a product (`ProductRequest`, optional `If-Match`) | 200 + ETag | 400, 404, 409 stale revision |
| POST | `/api/v1/products/{id}/activate` | Legal transition to ACTIVE | 200 + ETag | 404, 409, 422 |
| POST | `/api/v1/products/{id}/deactivate` | Legal transition to INACTIVE | 200 + ETag | 404, 409, 422 |
| GET | `/api/v1/products/{id}/fees` | Fees attached to one product | 200 array | 404 |

### Fees

| Method | Path | Purpose | Success |
|---|---|---|---|
| GET | `/api/v1/fees` | Bank-wide flattened fee schedule; optional `?type=&currency=&productCode=` | 200 array |

### Generic governed catalog (v2)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v2/product-types` | List immutable trusted schemas |
| GET | `/api/v2/product-types/{id}/versions/{version}` | Get one exact schema |
| POST | `/api/v2/product-types/{id}/versions/{version}/validate` | Validate attributes with ordered violations |
| POST / GET | `/api/v2/specifications[/{id}]` | Create/read canonical product identity |
| POST / GET | `/api/v2/offerings[/{id}]` | Create/read a market context |
| POST | `/api/v2/offerings/{id}/revisions` | Author a DRAFT revision |
| GET / PUT | `/api/v2/offerings/{id}/revisions/{revisionId}` | Read/update a draft; PUT requires `If-Match` |
| POST | `/api/v2/offerings/{id}/revisions/{revisionId}/publish` | Four-eyes publish with reason and `If-Match` |
| GET | `/api/v2/products/{offeringId}?effectiveAt=` | Resolve published effective content for one deterministic offering |

### Example — create a product

```http
POST /api/v1/products
Content-Type: application/json

{
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "baseRate": 0.03,
  "fees": [
    { "name": "Account Maintenance", "type": "MONTHLY", "amount": 0.0,
      "currency": "EUR", "frequency": "MONTHLY", "waivable": false }
  ]
}
```

```http
201 Created
Content-Type: application/json
ETag: "0"

{
  "id": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "revision": 0,
  "fees": [ … ],
  "updatedAt": "2026-06-09T10:00:00Z"
}
```

Only `code`, `name`, `type`, `currency` are required on `ProductRequest`; the rich configuration blocks (`cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig`) are optional. On create, an unspecified `status` defaults to `DRAFT` and `isPublic` to `true`.

### Example — fee schedule item

```http
GET /api/v1/fees?productCode=CURRENT_PERSONAL
```

```json
[
  {
    "id": "<canonical-product-uuid>:<feeId>",
    "code": "CURRENT_PERSONAL_FX_CONVERSION",
    "name": "FX Conversion",
    "type": "TRANSACTION",
    "amount": 1.5,
    "currency": "EUR",
    "frequency": "PERCENTAGE",
    "productId": "<canonical-product-uuid>",
    "productCode": "CURRENT_PERSONAL",
    "productName": "Personal Current Account",
    "status": "ACTIVE",
    "updatedAt": "2024-11-01T09:00:00Z"
  }
]
```

## Error model

Legacy v1 wire shapes remain compatible with the existing Pacts. v2 returns `ApiError`; schema
validation uses 422 with ordered `{instancePath,schemaPath,keyword,message}` violations.

## Versioning

- URL versioning: preserved `/api/v1`, newest `/api/v2`.
- The OpenAPI contract version (`openapi.yaml:info.version`) is the API-contract axis; any breaking change must bump it per `oasdiff` classification and update the contract + a contract test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
