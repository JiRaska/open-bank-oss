# API & contracts

## Base path

- **In-cluster base:** `http://openbank-product-catalog:8104/api/v1`
- **Local dev:** `http://localhost:8104/api/v1`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8085/q/openapi) on the management port (source of truth: `openapi.yaml`, `info.version` 1.1.0, OpenAPI 3.0.3)
- **Swagger UI:** `http://localhost:8085/api/docs` (configured via `quarkus.swagger-ui.path`, `always-include: true`)

The major of `openapi.yaml:info.version` equals `openbank.api.version` equals the URL `/api/v1` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)). The release version (`version.txt`) is a separate axis.

## Authentication

The service is an OIDC resource server. Reads require an authenticated bearer token; create/update/lifecycle mutations require `ROLE_OPERATOR` or `ROLE_ADMIN`. `@Authorize` adds the policy action, but OPA is advisory until the bank deployment enables an enforcing profile. Browser traffic still goes through the authenticated BFF (ADR-0056).

CORS origins are restricted to the configured admin UI origins and expose `ETag`; `If-Match` is allowed for optimistic writes.

## Idempotency

No `Idempotency-Key` mechanism is implemented. Create is guarded by unique product `code`. Single-product reads and mutations return a numeric `ETag`; send it as `If-Match` (or `revision` in the legacy body) to reject a stale writer with `409 Conflict`. v1 keeps the precondition optional for compatibility, so an unversioned legacy read-modify-write is not protected; v2 authoring requires it.

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

Legacy not-found responses retain `{ "error": "<message>" }` for the existing Pact. Validation, lifecycle and conflict errors use the fleet `ApiError` envelope with `traceId`, `status`, `code`, `message` and `timestamp`.

## Versioning

- URL versioning: `/api/v1`.
- The OpenAPI contract version (`openapi.yaml:info.version`) is the API-contract axis; any breaking change must bump it per `oasdiff` classification and update the contract + a contract test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
