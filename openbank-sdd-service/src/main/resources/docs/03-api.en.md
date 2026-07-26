# API

The REST contract is formalised in [`openapi.yaml`](../openapi.yaml) (`info.version: 0.2.0`, OpenAPI 3.0.3). It is served at `/q/openapi`, with Swagger UI at `/api/docs`. All paths are under `/api/v1` — the major version is the URL API version (ADR-0048).

Base path: `/api/v1/sdd`. Content type: `application/json`.

## Endpoints

| Method | Path | Summary | Roles |
|---|---|---|---|
| `POST` | `/api/v1/sdd/mandates` | Register a debtor mandate (Core ⇒ ACTIVE, B2B ⇒ PENDING_CONFIRMATION). Idempotent on `(CID, UMR)`. | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `GET` | `/api/v1/sdd/mandates?accountId={uuid}` | List an account's mandates | + VIEWER |
| `GET` | `/api/v1/sdd/mandates/{id}` | Fetch a single mandate | + VIEWER |
| `PATCH` | `/api/v1/sdd/mandates/{id}` | Amend a mandate field (records an AMDT marker) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/confirm` | Confirm a B2B mandate (PENDING_CONFIRMATION → ACTIVE) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/suspend` | Suspend an ACTIVE mandate | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/resume` | Resume a SUSPENDED mandate | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/cancel` | Cancel a mandate (terminal) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `GET` | `/api/v1/sdd/mandates/{id}/refund-assessment?debitDate={date}&asOf={date}` | Assess a post-settlement refund claim | + VIEWER |
| `POST` | `/api/v1/sdd/collections/authorise` | Fail-closed authorisation of an inbound collection | OPERATOR/ADMIN/PAYMENTS/SERVICE |

## Register a mandate

`POST /api/v1/sdd/mandates`

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "debtorIban": "CZ6508000000192000145399",
  "creditorIdentifier": "DE98ZZZ09999999999",
  "umr": "UMR-2026-000123",
  "scheme": "CORE",
  "sequenceType": "RCUR",
  "creditorName": "Acme Utilities a.s.",
  "debtorName": "Jan Novák",
  "signatureDate": "2026-01-15"
}
```

- **Idempotent on the natural key.** Re-registering the same `(creditorIdentifier, umr)` returns the **already-stored** mandate (HTTP `201`) rather than creating a duplicate. There is **no `Idempotency-Key` header** — idempotency is keyed off the rulebook pair, not a client-supplied token.
- **Birth status:** `CORE` ⇒ `ACTIVE`; `B2B` ⇒ `PENDING_CONFIRMATION` (must be confirmed before it can authorise a collection).
- Returns `201 Created` with the `Mandate` body.

## Authorise a collection

`POST /api/v1/sdd/collections/authorise`

```json
{
  "creditorIdentifier": "DE98ZZZ09999999999",
  "umr": "UMR-2026-000123",
  "scheme": "CORE",
  "sequenceType": "RCUR",
  "amount": 49.90,
  "currency": "EUR",
  "dueDate": "2026-03-01",
  "controls": { "blockAll": false, "blockedCreditors": [], "maxAmountPerCollection": 100.00 }
}
```

Returns `200` with an `AuthorisationDecision`:

```json
{ "decision": "ACCEPT", "reasonCode": null, "reason": null }
```

The decision is fail-closed and evaluated in order. EPC reason codes are attached on a non-accept:

| Decision | Meaning | Example reason code |
|---|---|---|
| `ACCEPT` | All checks pass; the collection is stamped on the mandate and `sdd.collection.authorised.v1` is emitted for the downstream posting path. | — |
| `REJECT` | Technical / mandate fault — no/invalid mandate, not ACTIVE, scheme mismatch, non-EUR currency, unverified B2B, one-off already used. | `MD01`, `FF05` |
| `REFUSE` | Mandate is fine but the debtor exercised a control — block-all, block-listed creditor, amount over the per-collection cap. | `MS02` |

Order of checks: mandate present & ACTIVE → scheme match → **EUR-only** → B2B verified → one-off-reuse → debtor controls (block-all / block-list / amount cap).

## Refund assessment

`GET /api/v1/sdd/mandates/{id}/refund-assessment?debitDate=2026-03-01&asOf=2026-04-01`

Returns `200` with a `RefundAssessment` (`asOf` defaults to today):

```json
{ "eligible": true, "kind": "UNCONDITIONAL", "reasonCode": "MD06", "reason": null }
```

- **Authorised Core:** `UNCONDITIONAL` refund within 8 weeks (56 days) of the debit date; beyond that, ineligible.
- **Authorised B2B:** no post-settlement refund right.
- **Unauthorised:** `UNAUTHORISED` refund within 13 months (handled where no mandate exists; not modelled as a use case in v1).

## Versioning

- **API contract version:** `openapi.yaml:info.version = 0.2.0`; URL major `v1` (ADR-0048).
- **Release version:** `version.txt` (independent axis, release-please-owned).
- `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by `openbank-libs`.

## Error model

Errors are returned as a small JSON object. No problem+json envelope is used in v1.

| Status | When | Body |
|---|---|---|
| `404 Not Found` | unknown mandate id | `{ "error": "No SDD mandate <id>", "mandateId": "<id>" }` |
| `409 Conflict` | illegal lifecycle transition (e.g. confirm a non-PENDING mandate, suspend a non-ACTIVE mandate, amend a terminal/pending mandate) | `{ "error": "Illegal mandate transition: <from> -> <to>" }` |
| `201 / 200` | success | the `Mandate` / decision body |

Mandate faults are mapped by `MandateNotFoundMapper` (404) and `IllegalMandateTransitionMapper` (409). The `authorise` and `refund-assessment` endpoints never throw on a business "no" — they return a structured decision with the EPC reason code.

## Authentication & authorisation

- **AuthN:** Keycloak OIDC, RS256 JWT bearer token (`auth-server-url .../realms/openbank`, client `openbank-services`).
- **AuthZ:** Quarkus `@RolesAllowed`. Mutations require one of `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API`; read endpoints (`GET` list/fetch, `GET refund-assessment`) additionally allow `ROLE_VIEWER`.
- CORS is restricted to `http://localhost:3000` in the shipped config; security headers (CSP, HSTS, X-Frame-Options DENY, nosniff) are set on every response.
