# API & contracts

## Base path

- **In-cluster base:** `http://openbank-product-catalog:8104/api/v1`
- **Local dev:** `http://localhost:8104/api/v1`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8104/q/openapi) (source of truth: `openapi.yaml`, `info.version` 0.1.0, OpenAPI 3.1.0)
- **Swagger UI:** `/api/docs` (configured via `quarkus.swagger-ui.path`, `always-include: true`)

The major of `openapi.yaml:info.version` equals `openbank.api.version` equals the URL `/api/v1` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)). The release version (`version.txt`) is a separate axis.

## Authentication

There is **no service-level authentication wired in the code today** — the resources are not annotated with `@RolesAllowed`, and no Keycloak/OIDC extension is on the classpath. The service relies on:

- the API gateway / in-cluster network boundary for access control, and
- CORS + security response headers configured in `application.yaml` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`, HSTS, `Permissions-Policy`, `Referrer-Policy`).

CORS origins are restricted to `http://localhost:3000` and `http://openbank-admin-ui:3000`. Adding role-gated mutations (e.g. a `ROLE_PRODUCT_ADMIN` for create/update/activate) is a sensible hardening follow-up — see [06 — Compliance](./06-compliance.md).

## Idempotency

No `Idempotency-Key` mechanism is implemented. Create is naturally guarded by **unique product `code`**: a duplicate code returns `409 Conflict`. Updates and lifecycle transitions are by `id` and are effectively idempotent (repeating an activate yields the same ACTIVE state).

## Endpoints

### Products

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| GET | `/api/v1/products` | List products; optional `?type=&status=&currency=` filters | 200 array | — |
| GET | `/api/v1/products/{id}` | Get one product | 200 | 404 |
| POST | `/api/v1/products` | Create a product (`ProductRequest`) | 201 | 409 duplicate code |
| PUT | `/api/v1/products/{id}` | Update a product (`ProductRequest`) | 200 | 404 |
| POST | `/api/v1/products/{id}/activate` | Set status ACTIVE | 200 | 404 |
| POST | `/api/v1/products/{id}/deactivate` | Set status INACTIVE | 200 | 404 |
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

{
  "id": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
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
    "id": "prod-003:<feeId>",
    "code": "CURRENT_PERSONAL_FX_CONVERSION",
    "name": "FX Conversion",
    "type": "TRANSACTION",
    "amount": 1.5,
    "currency": "EUR",
    "frequency": "PERCENTAGE",
    "productId": "prod-003",
    "productCode": "CURRENT_PERSONAL",
    "productName": "Personal Current Account",
    "status": "ACTIVE",
    "updatedAt": "2024-11-01T09:00:00Z"
  }
]
```

## Error model

Errors are returned as a minimal JSON object `{ "error": "<message>" }` with the appropriate HTTP status (`404` not found, `409` duplicate code). This is the `Error` schema in `openapi.yaml`. There is no shared RFC-7807 problem+json envelope wired here yet — aligning on the platform error envelope is a follow-up.

## Versioning

- URL versioning: `/api/v1`.
- The OpenAPI contract version (`openapi.yaml:info.version`) is the API-contract axis; any breaking change must bump it per `oasdiff` classification and update the contract + a contract test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
