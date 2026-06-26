# API

The REST contract is formalised in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). All endpoints live under `/api/v1/tpp-registry` — the URL major version (`v1`) corresponds to `openbank.api.version = "1"` (ADR-0048). Swagger UI is served at `/api/docs`.

> **Contract/code note:** `openapi.yaml` uses the `permission` query parameter with enum `[AIS, PIS, PIIS]` on `/check`, whereas the implemented `TppRegistryResource` reads a `role` query parameter mapped to the `TppRole` enum `[AISP, PISP, PIISP, ASPSP]`. Treat the code as the running behaviour; the OpenAPI naming is being reconciled. Likewise the OpenAPI `servers` block still shows a placeholder port (`8123`); the service actually binds **8108**.

## Endpoints

| Method | Path | Purpose | Auth | Idempotent |
|---|---|---|---|---|
| `GET` | `/api/v1/tpp-registry/check` | Authorization check for a TPP+role | OIDC | n/a (read) |
| `POST` | `/api/v1/tpp-registry` | Register a new TPP | OIDC | `Idempotency-Key` |
| `GET` | `/api/v1/tpp-registry` | List / filter TPPs | OIDC | n/a (read) |
| `GET` | `/api/v1/tpp-registry/{tppId}` | Get one TPP | OIDC | n/a (read) |
| `POST` | `/api/v1/tpp-registry/{tppId}/blacklist` | Blacklist a TPP | OIDC + `@Authorize` | `Idempotency-Key` |
| `POST` | `/api/v1/tpp-registry/sync/eba` | Trigger EBA register sync | OIDC | `Idempotency-Key` (300 s TTL) |
| `GET` | `/api/v1/tpp-registry/sync/state` | Read last sync state | OIDC | n/a (read) |

### `GET /check`

Query params: `tppId` (required), `role` (required, one of `AISP|PISP|PIISP|ASPSP`).
Returns `200` with a `TppAuthorizationResult` when authorised, **`403`** with the same body (carrying a `reason`) when not. Rejection reasons: not found, status not ACTIVE, role not held, QWAC certificate expired.

### `POST /api/v1/tpp-registry` — register

Body: `RegisterTppRequest` (`tppId`, `name`, `permissions`/roles, `eidasCertFingerprint`, `countryCode`, `nca`). The implementation maps to `RegisterTppCommand` (`tppId`, `name`, `countryCode`, `nca`, `roles`, `qwacSubjectDn?`, `qsealSubjectDn?`). New entries are created `ACTIVE`. Duplicate `tppId` → `409 CONFLICT`. Returns `201` with the created `TppEntry`.

### `GET /api/v1/tpp-registry` — list

Query params (all optional): `countryCode`, `role`, `status`, `limit` (default 50), `afterCursor`. Returns `200` with `{ "tpps": [...], "count": n }`.

### `POST /{tppId}/blacklist`

Body: `{ "reason": "<text>" }` (defaults to "No reason provided" if absent). Sets status `BLACKLISTED`, records `blacklistedAt` and `blacklistReason`. Guarded by `@Authorize(action = "tppRegistry.blacklist", resource = "#tppId")` (OPA). Returns `200` with the updated `TppEntry`.

### `POST /sync/eba` & `GET /sync/state`

`POST /sync/eba` triggers `attemptEbaSync` (fault-tolerant) and persists the resulting `EbaRegisterSyncState`. Currently a stub returning `errorMessage = "EBA sync not yet implemented — manual registration only"`. `GET /sync/state` returns the last persisted state (or a zeroed default).

## Idempotency

Mutating endpoints accept the `Idempotency-Key` header. The first request executes and the response is cached in Redis (`IdempotencyStore` from openbank-libs) under an operation-scoped key:

- register: `tpp:register:{tppId}:{key}`
- blacklist: `tpp:blacklist:{tppId}:{key}`
- sync: `tpp:sync:{key}` (300 s TTL)

A replay returns the cached status + body with header `X-Idempotency-Replayed: true`. A blank/missing key skips caching (the operation still executes).

## Error model

`ExceptionMappers` produce JSON bodies of the shape `{"error": "<CODE>", "message": "..."}`:

| Exception | HTTP | `error` |
|---|---|---|
| `TppNotFoundException` | 404 | `NOT_FOUND` |
| `TppAlreadyExistsException` | 409 | `CONFLICT` |
| `EbaSyncUnavailableException` | 503 | `SERVICE_UNAVAILABLE` |
| `IllegalArgumentException` | 400 | canonical libs `ApiError` (traceId/code/status) — ADR-0049 D4 |

The OpenAPI `ApiError` schema (`code`, `message`) documents the canonical libs error contract used for `404`/validation responses.

## Versioning

- **API contract axis:** `openapi.yaml:info.version` (`1.0.0`); URL major `v1` == `openbank.api.version`. An API change classifies its own bump from the OpenAPI diff (`oasdiff`), independent of the release version (ADR-0048).
- `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by openbank-libs.
