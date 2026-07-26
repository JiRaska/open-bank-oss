# API

The REST contract is described in [`openapi.yaml`](../openapi.yaml) (OpenAPI **3.1.0**, `info.version: 1.0.0`, title *Dispute Service API*). All paths are served under `/api/v1` (ADR-0048 — the URL major matches the API contract major). Swagger UI is exposed at `/api/docs`.

> **Contract drift to be aware of:** the committed `openapi.yaml` lists `servers.url: http://localhost:8113`, but the service actually binds to **8135** (`application.yaml`). The `OpenDisputeRequest` schema in `openapi.yaml` also omits `partyId` and `transactionDate`, which the Kotlin `OpenDisputeRequest` DTO **requires**. The Kotlin source is authoritative; the OpenAPI file needs reconciliation (TBD).

## Base & content

- **Base path:** `/api/v1/disputes`
- **Media type:** `application/json` (consumes & produces)
- **Auth:** Bearer JWT (Keycloak OIDC). Class-level `@RolesAllowed("ROLE_VIEWER","ROLE_OPERATOR","ROLE_ADMIN","ROLE_API")`; reads allow `ROLE_VIEWER`, mutations require `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API`.

## Endpoints

| Method | Path | Roles | Summary |
|---|---|---|---|
| `POST` | `/api/v1/disputes` | OPERATOR/ADMIN/SERVICE | Open a new dispute → `201` |
| `GET` | `/api/v1/disputes?status=` | any role | List disputes by status (defaults to `OPEN`) |
| `GET` | `/api/v1/disputes/{id}` | any role | Get dispute by ID → `200` / `404` |
| `PUT` | `/api/v1/disputes/{id}` | OPERATOR/ADMIN/SERVICE | Update status / resolution (`@Authorize dispute.update`) |
| `GET` | `/api/v1/disputes/reference/{ref}` | any role | Get dispute by reference → `200` / `404` |
| `GET` | `/api/v1/disputes/account/{accountId}` | any role | List disputes for an account |
| `POST` | `/api/v1/disputes/{id}/evidence` | OPERATOR/ADMIN/SERVICE | Add evidence → `201` |
| `GET` | `/api/v1/disputes/{id}/evidence` | any role | List evidence |
| `POST` | `/api/v1/disputes/{id}/withdraw?actor=` | OPERATOR/ADMIN/SERVICE | Withdraw a dispute |
| `POST` | `/api/v1/disputes/{id}/escalate?actor=` | OPERATOR/ADMIN/SERVICE | Escalate a dispute |
| `GET` | `/api/v1/disputes/{id}/timeline` | any role | Get the dispute timeline |

## Request — open a dispute

`POST /api/v1/disputes` (body, per the Kotlin DTO):

```json
{
  "transactionId": "uuid",
  "accountId": "uuid",
  "partyId": "uuid",
  "disputeType": "UNAUTHORIZED",
  "amount": 129.90,
  "currency": "EUR",
  "description": "Card not present, not me",
  "merchantName": "ACME GmbH",
  "merchantId": "MID-123",
  "transactionDate": "2026-05-30"
}
```

`disputeType` ∈ `UNAUTHORIZED | DUPLICATE | GOODS_NOT_RECEIVED | NOT_AS_DESCRIBED | CREDIT_NOT_PROCESSED | TECHNICAL_ERROR | OTHER`. The server assigns `reference`, sets `status=OPEN`, `resolution=PENDING` and `resolutionDeadline = today + 45 days`.

## Request — update

`PUT /api/v1/disputes/{id}` — all fields optional:

```json
{ "status": "RESOLVED_CUSTOMER", "resolution": "CHARGEBACK", "chargebackAmount": 129.90, "resolvedBy": "operator-42" }
```

`status` ∈ `OPEN | UNDER_REVIEW | PENDING_CUSTOMER | PENDING_MERCHANT | RESOLVED_CUSTOMER | RESOLVED_MERCHANT | WITHDRAWN | ESCALATED`. Reaching `RESOLVED_*` or `WITHDRAWN` stamps `resolvedAt`.

## Idempotency

`Idempotency-Key` is permitted by CORS configuration, but the service does **not yet enforce** idempotent replay on mutations (no idempotency store wiring is present in `DisputeResource`). `open` produces a time-based reference (`DSP-<epochMillis>`); a retried `POST` will create a new dispute. Hardening idempotency is a tracked follow-up (TBD).

## Error model

Errors use the `ApiError` schema `{ "code": "...", "message": "..." }` for `404`. Note the resource currently maps unexpected failures on `open`/`update` to `500` with a `{ "error": "<message>" }` body rather than the structured `ApiError`; `GET /{id}` returns `404` (empty) when not found. Aligning error responses to a single envelope is a follow-up (TBD).

## Versioning

- URL major: `/api/v1` (== `openbank.api.version`).
- API-contract version: `openapi.yaml:info.version` = `1.0.0` — independent of the release version (`version.txt` / `quarkus.application.version`, ADR-0048).
- `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.
