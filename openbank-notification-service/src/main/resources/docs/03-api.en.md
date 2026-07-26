# API

The REST contract is formalized in [`openapi.yaml`](../../openapi.yaml) (`Notification Service API`, OpenAPI 3.1.0, `info.version 1.2.0`). All endpoints are under `/api/v1` — the URL major version equals `openbank.api.version` (`1`), per [ADR 0048](../../../../docs/adr/0048-two-version-axes.md). The interactive Swagger UI is served at `/api/docs`, the raw spec at `/q/openapi`.

> The bulk of business traffic is **not** REST — it is the Kafka consumer (`openbank.notification.requests`). The REST surface is for device registration, read access, and the dispatch-control break-glass workflow.

## Authentication & authorization

Keycloak OIDC (`realms/openbank`, client `openbank-services`), RS256 bearer tokens. Roles:

| Surface | Roles |
|---|---|
| Read notifications (`GET /notifications…`) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |
| List devices (`GET /devices`) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |
| Register device (`POST /devices`) | `ROLE_OPERATOR`, `ROLE_API`, `ROLE_ADMIN` |
| Dispatch control (`/ops/dispatch…`) | `ROLE_OPERATOR`, `ROLE_ADMIN` (read also `ROLE_AUDITOR`) |

For dispatch-control, the **actor identity is taken from the authenticated JWT subject**, never from the request body — so the four-eyes rule cannot be spoofed. `ROLE_SRE` is the intended operator role once it exists in the realm; until then it is gated on `ROLE_OPERATOR`/`ROLE_ADMIN`.

## Notifications

### `GET /api/v1/notifications`

List notifications, paginated. Query params: `partyId` (uuid, optional), `page` (default 0), `size` (default 20, clamped 1..100). The OpenAPI spec also documents `status` and `offset`/`limit`; the implementation uses `page`/`size` and filters by `partyId`.

Returns `{ items: [...], total, page, size }`. Each item: `id, partyId, channel, template, recipient, subject, status, sentAt, createdAt`.

### `GET /api/v1/notifications/{id}`

Get a single notification by `notificationId` (uuid). Returns the full record including `body`. `404` if not found.

## Devices (push token registry)

### `POST /api/v1/devices`

Register (upsert) a push device token for a party. Body: `RegisterDeviceRequest` — `partyId` (uuid, required), `platform` (`FCM`|`APNS`, required), `token` (provider-issued, required), `appInstance` (required), `appVersion?`, `osVersion?`. Re-registration of the same `(platform, token)` upserts.

- `201` → `DeviceResponse` (the token is **never echoed back**).
- `400` → `ApiError` for missing/invalid fields.

The customer app reaches this through `openbank-customer-edge`, which injects the authoritative `partyId` from the customer JWT — the body's `partyId` is never trusted on its own (IDOR prevention).

### `GET /api/v1/devices?partyId={uuid}`

List a party's registered devices. `partyId` is required (`400` otherwise). Listings expose only non-sensitive metadata (id, platform, status, appInstance, versions, dates) — **never the token**.

## Ops — Dispatch Control (break-glass, ADR-0047)

### `GET /api/v1/ops/dispatch`

Returns the current desired-state snapshot plus recent history: `{ current: DispatchControlSnapshot, history: [...] }`. A snapshot is `{ controlKey, state (ENABLED|HALTED), version, reason, actor, effectiveFrom, deferredReviewRequired }`.

### `POST /api/v1/ops/dispatch/halt`

Break-glass — halt dispatch immediately (single actor). Body `{ reason? }`. Returns the new HALTED snapshot with `deferredReviewRequired=true`.

### `POST /api/v1/ops/dispatch/resume/propose`

Propose a resume (four-eyes). Body `{ reason? }`. `202` → `{ proposalId, state: PROPOSED }`.

### `POST /api/v1/ops/dispatch/resume/{proposalId}/approve`

Approve and execute a resume. The approver **must differ** from the proposer. `200` → new ENABLED snapshot. `422` (`ApiError`) on a four-eyes violation (approver == proposer). `404` if the proposal is unknown.

### `POST /api/v1/ops/dispatch/resume/{proposalId}/reject`

Reject a pending proposal. `200` → `{ proposalId, state: REJECTED }`. `404` if unknown.

## Error model

JSON `ApiError` `{ code, message }`. Shared exception mappers in `openbank-libs` translate:

| Condition | HTTP |
|---|---|
| Missing / invalid field | `400` `BAD_REQUEST` |
| Resource / proposal not found | `404` |
| Four-eyes violation (`MakerCheckerViolation`) | `422` |

## Idempotency

There is **no `Idempotency-Key` layer** on this service. The inbound Kafka path is at-least-once and a redelivery re-persists a fresh notification row (acceptable — no money path). Device registration is naturally idempotent via the `(platform, token)` unique upsert.

## Versioning (two axes — ADR-0048)

- **API contract:** `openapi.yaml:info.version = 1.2.0`; URL major `/api/v1` == `openbank.api.version = 1`. An API change classifies its own bump from the OpenAPI diff.
- **Release:** `version.txt = 0.4.0`, owned by release-please. The two are independent and must not be forced equal.

> **Spec note:** `openapi.yaml` `servers[0].url` lists port `8125`; the service actually listens on `8112` (see `application.yaml`). Treat `8112` as authoritative; the spec server URL is a stale example.
