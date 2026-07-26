# API

The REST contract is described in [`openapi.yaml`](../openapi.yaml) (`info.version: 1.1.0`). All business endpoints are served under the URL prefix `/api/v1` (`openbank.api.version = 1`, ADR-0048). The spec is also served live at `/q/openapi` and rendered at `/api/docs` (Swagger UI).

> **Spec / code drift note:** the committed `openapi.yaml` is slightly ahead of / behind the implementation in a few places (e.g. the `checkType` enum values and the request-body field names). Where they differ, the tables below reflect the **actual `KycResource` implementation**, which is authoritative for callers today. Reconciling the OpenAPI document with the resource is tracked as a contract-test follow-up.

## Endpoints (as implemented in `KycResource`)

| Method | Path | Roles | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/kyc/cases?page=&size=&status=` | `ROLE_VIEWER`,`ROLE_OPERATOR`,`ROLE_ADMIN`,`ROLE_KYC`,`ROLE_COMPLIANCE`,`ROLE_API` | Paginated list; optional `status` funnel filter (ADR-0068) |
| `POST` | `/api/v1/kyc/cases` | `ROLE_OPERATOR`,`ROLE_ADMIN`,`ROLE_KYC` | Open a new case for a party |
| `GET` | `/api/v1/kyc/cases/{id}` | viewer/operator/admin/kyc/compliance/service | Get case by id |
| `GET` | `/api/v1/kyc/cases/party/{partyId}` | viewer/operator/admin/kyc/compliance/service | Get latest case for a party (404 if none) |
| `PUT` | `/api/v1/kyc/cases/{id}/checks/{checkType}` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.updateCheck)` | Record a check result |
| `POST` | `/api/v1/kyc/cases/{id}/approve` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.approve)` | Approve (four-eyes, ADR-0068) |
| `POST` | `/api/v1/kyc/cases/{id}/reject` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.reject)` | Reject (four-eyes, ADR-0068) |

### Request bodies (implementation)

- **Open case** — `{ "partyId": "<uuid>" }`. The case is created with status `OPEN`, risk `MEDIUM`, a 30-day `expiresAt`, and four PENDING checks (IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING).
- **Update check** — `{ "status": "PENDING|PASSED|FAILED|MANUAL_REVIEW", "result": "<note?>" }`; `{checkType}` is one of `IDENTITY`, `ADDRESS`, `PEP_SCREENING`, `SANCTIONS_SCREENING`, `ADVERSE_MEDIA`. When all checks pass the case moves to `UNDER_REVIEW`; if any check fails it moves to `REJECTED`.
- **Approve** — `{ "reviewedBy": "<reviewer>" }`.
- **Reject** — `{ "reviewedBy": "<reviewer>", "reason": "<text>" }`.

> The `openapi.yaml` documents the update-check field as `result` (enum) + `notes`, and review bodies keyed on `reviewerId`; the running code uses `status`/`result` and `reviewedBy`. Trust the code shapes above.

## Response model

`KycCaseResponse` returns the `KycCase` aggregate: `id`, `partyId`, `caseType`/`status`, `riskLevel`, `checks` (map of check → status), `createdAt`, `updatedAt`. The list endpoint returns an envelope `{ items, total, page, size, statusFilter }`.

## Versioning

- **API contract version** lives in `openapi.yaml:info.version` (currently `1.1.0`); its major equals the URL `/api/v{N}` and `openbank.api.version` (ADR-0048).
- **Release version** is independent and tracked in `version.txt` (currently `0.2.0`), owned by release-please.
- The shared `openbank-libs` filter serves `X-API-Version` / `X-Service-Version` headers and `/api/v1/info`.

## Idempotency

This service does **not** use a per-request `Idempotency-Key` cache. Idempotency is enforced at the domain level:

- `openCaseForParty` (the `PARTY_CREATED` consumer path) first re-reads by `partyId`, and the partial unique index `uq_kyc_cases_active_party` (V5) rejects a racing second insert — so replaying the party stream never creates duplicate open cases.
- The manual `POST /cases` endpoint is not de-duplicated by a key; it is an operator action behind a role check.

## Error model

Errors use the shared `com.openbank.libs.api.error.ApiError` (`{ id, status, code, message }`). A missing case raises `KycCaseNotFoundException`, mapped by `KycNotFoundMapper` to **HTTP 404** with `code = NOT_FOUND`. `GET /cases/party/{partyId}` returns a bare 404 when no case exists.

## Auth

- **AuthN:** Keycloak OIDC, RS256 JWT bearer (`bearerAuth`). OIDC is disabled in `%dev` and `%test`.
- **AuthZ:** Quarkus `@RolesAllowed` per endpoint, plus `@Authorize` on the four-eyes / check mutations evaluated against the OPA sidecar (advisory by default — `authz.enforce=false`, ADR-0034).
