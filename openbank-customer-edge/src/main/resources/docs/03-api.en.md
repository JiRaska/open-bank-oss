# API & contracts

## Base path

- **Public base:** `https://customer.open-bank.tech/customer/v1` (sandbox)
- **In-cluster app port:** 8128
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8128/q/openapi) — source: `src/main/resources/openapi.yaml` (`info.version: 1.6.0`)

The contract **is** formalised in `openapi.yaml`. The URL prefix is `/customer/v1` (note: this is the edge's own customer-facing version axis, distinct from each upstream's `/api/v1`).

## Authentication

### Inbound (caller → edge)

A **Keycloak Bearer token** from the `openbank-customers` realm. The issuer is pinned to the realm's public URL (`QUARKUS_OIDC_TOKEN_ISSUER`, default `https://kc.open-bank.tech/realms/openbank-customers`); roles are read from `realm_access/roles`.

| Route | Auth |
|---|---|
| `POST /onboarding/start` | **anonymous** (`@PermitAll`, hosted in `OnboardingResource`) |
| everything else | `ROLE_CUSTOMER` required (`@RolesAllowed`, class-level on `CustomerEdgeResource`) |

Lazy authentication (`quarkus.http.auth.proactive=false`) keeps the public route truly anonymous while all other routes still 401 without a valid customer token.

### Outbound (edge → upstream)

The customer token is **not** forwarded. The edge fetches a service-account token via `client_credentials` against the operator `openbank` realm (`openbank-edge` client; secret supplied at runtime via `OPENBANK_UPSTREAM_CLIENT_SECRET`) and passes the caller's party via the `X-Customer-Party-Id` header.

## Idempotency

The edge does **not** store idempotency keys. For routes whose upstream requires one, the caller's `Idempotency-Key` header is forwarded:

- **`POST /domestic-payments`, `POST /sepa-payments`** — `Idempotency-Key` is **required** by the contract and forwarded so an app retry replays rather than duplicates.
- **`POST /sca/challenges`** — `Idempotency-Key` is optional and forwarded when present.
- **`POST /onboarding/start`** — the edge generates a fresh key per call (each onboarding attempt is a distinct party); a stable client-supplied key is a future enhancement.
- A blank/absent key on a forwarded POST falls back to an edge-generated UUID so the upstream contract is always satisfied.

## Endpoint summary

All paths are under `/customer/v1`. Scopes are the OAuth scopes declared in `openapi.yaml`.

| Method & path | Scope | Notes |
|---|---|---|
| `GET /accounts` | `accounts:read` | list the caller's accounts |
| `GET /accounts/{accountId}` | `accounts:read` | 403 if not owner |
| `GET /balances/{accountId}` | `accounts:read` | 403 if not owner |
| `GET /transactions?accountId=&limit=&cursor=` | `accounts:read` | ownership-enforced; `cursor` URL-encoded |
| `GET /statements/{accountId}` | `accounts:read` | period-close list |
| `GET /statements/{accountId}/{currency}/{legalSequence}?format=` | `accounts:read` | render camt.053 / MT940 / PDF; format & currency allow-listed |
| `GET /notifications?limit=&page=` | `accounts:read` | party-scoped page, total and exact unread count |
| `GET /notifications/unified?limit=&partyId=` | `accounts:read` | latest 1–100 items across the person and current company mandates; optional authorized origin filter |
| `GET /notifications/{id}?partyId=` | `accounts:read` | notification detail; origin `partyId` from unified items selects an authorized profile |
| `PATCH /notifications/{id}/read?partyId=` | `accounts:read` | mark an authorized origin notification read |
| `GET /profile` | `accounts:read` | the caller's own party profile |
| `POST /domestic-payments` | `payments:initiate` | enriched; `Idempotency-Key` required |
| `POST /sepa-payments` | `payments:initiate` | enriched; `Idempotency-Key` required |
| `POST /sca/parties/{partyId}/devices` | `sca:enroll-device` | 403 if partyId ≠ JWT party |
| `POST /sca/challenges` | `sca:decide` | partyId injected from JWT |
| `GET /sca/challenges/{id}` | `sca:decide` | challenge status |
| `POST /sca/challenges/{id}/decision` | `sca:decide` | record device decision |
| `POST /devices` | `accounts:read` | register push token; partyId from JWT |
| `GET /devices` | `accounts:read` | list devices (tokens never returned) |
| `POST /onboarding/start` | *(anonymous)* | create `PENDING_ACTIVATION` party |
| `POST /onboarding/account` | `accounts:read` | open first account after KYC gate |
| `GET /products/term-deposits` | `accounts:read` | discover active public term-deposit offers |
| `GET /products/term-deposits/{productId}` | `accounts:read` | read one offer and its conditions |
| `POST /term-deposits` | `accounts:read` | open a term-deposit account after KYC |
| `GET /holdings` | `wealth:read` | the caller's declared off-platform holdings (ADR-0301) |
| `POST /holdings` | `wealth:write` | declare a holding; the valuation source is always `CUSTOMER_DECLARED` |
| `GET /holdings/{holdingId}` | `wealth:read` | 404 unless the caller owns it |
| `PUT /holdings/{holdingId}/valuation` | `wealth:write` | revalue; the previous value stays in the history |
| `GET /holdings/{holdingId}/valuations` | `wealth:read` | valuation history, newest first |
| `DELETE /holdings/{holdingId}` | `wealth:write` | withdraw; 409 `HOLDING_LOCKED` while pledged |

### Unified notification inbox

Omitting `partyId` merges the authenticated person and every company in the current mandate inventory. Each item carries its origin `partyId`; pass that value to the detail and mark-read routes when opening a company item. An explicit `partyId` filter must belong to the person or a current mandate. The response contains the latest 1–100 items with `page: 0`; use the existing selected-profile list for older history. `total` and `unreadCount` sum the party responses seen during this request, so concurrent writes do not form one atomic snapshot. A failed mandate inventory returns 503; an unauthorized filter returns 403; a failed or mismatched party feed returns 502 without partial items.

## Selected requests

### Initiate a domestic payment (enriched)

The app sends a lightweight body; the edge resolves the debtor IBAN→BBAN + legal name and forwards the full instruction.

```http
POST /customer/v1/domestic-payments
Authorization: Bearer <customer JWT>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b
Content-Type: application/json

{
  "debtorAccountId": "11111111-1111-1111-1111-111111111111",
  "amount": "250.00",
  "currency": "CZK",
  "creditorAccountNumber": "2000145399/0800",
  "creditorName": "Jan Novák",
  "variableSymbol": "12345",
  "reference": "Invoice 2026-1"
}
```

The edge enriches `debtorAccountNumber`/`debtorBankCode` (from the account's Czech IBAN), `debtorName` (party-service), splits the creditor `number/bankcode`, maps `reference`→`messageForPayee`, defaults `priority=STANDARD`, then forwards to `domestic-payment-service`. Money does not move — initiation creates and screens only; settlement is SCA-gated.

The domestic rail accepts CZK only. Another currency returns `400 CURRENCY_NOT_ALLOWED` before account lookup, delegated spend reservation, or SCA; the `Idempotency-Key` remains available for a valid retry. A caller without debit authority receives the same `403` whether the account or a delegation exists, so this response does not reveal either.

### Start onboarding (anonymous)

```http
POST /customer/v1/onboarding/start
Content-Type: application/json

{ "partyType": "INDIVIDUAL", "legalName": "Jana Nováková", "email": "jana@example.com" }
```

```http
201 Created
{ "partyId": "…", "status": "PENDING_ACTIVATION" }
```

### Open first account after KYC

`POST /onboarding/account` requires `ROLE_CUSTOMER`. The edge reads the party from party-service and forwards to account-service **only if `status == ACTIVE`**, injecting `partyId` from the JWT.

### Term deposits

The app discovers offers through `GET /products/term-deposits`; the edge filters the operator
catalogue to products that are `ACTIVE`, public and valid today. Each offer carries its rate, fixed
term, deposit limits, early-withdrawal conditions and terms links. To open one, call
`POST /term-deposits` with only `{ "productId": "…" }` and an `Idempotency-Key`. The edge derives
`TERM_DEPOSIT` and the currency from that offer rather than trusting client-supplied values, then
applies the same ACTIVE-KYC and authoritative-legal-name gate as account opening. Creating the
account is separate from funding it: the app follows with its normal account-funding flow.

### Company payment batch drafts

`/business/payment-batches` lists and creates drafts; `/{id}` reads one draft and
`/{id}/items` replaces its items. These routes are disabled by default with
`openbank.edge.business-payment-batches.enabled=false` and return 404 until explicitly enabled.
They require a customer token and `X-Acting-For` naming a company for which the human caller has
an active mandate. The edge derives both parties from the token and mandate inventory; the body
cannot select them. It sends the company and human identities to domestic-payment using service
credentials. A draft does not submit or dispatch money.

Creation requires `Idempotency-Key` (1–128 characters). An exact replay returns 200 with the
existing draft; a conflicting body returns 409. List pages contain at most 20 drafts. Detail
returns at most 20 items per page; item replacement needs `If-Match` with the current revision
and returns 409 on a stale revision. Successful responses expose the server-computed item count,
total minor units, revision and timestamps as specified in `openapi.yaml`.

## Error model

The edge returns small JSON error envelopes of the shape `{"error":"…"}` it generates itself, and otherwise **passes the upstream status and body through unchanged**.

| HTTP | When |
|---|---|
| 400 | malformed/incomplete body, bad currency / debtor IBAN / creditor account, unsupported statement format |
| 401 | missing / invalid customer token (Quarkus OIDC challenge) |
| 403 | account/debtor not owned by the caller, `partyId` mismatch, missing `party_id`/`sub` claim |
| 404 | route not in the allow-list; party not found on `POST /onboarding/account` |
| 422 | `POST /onboarding/account` — KYC not approved (party status ≠ `ACTIVE`) |
| 502 | upstream transport failure (`{"error":"upstream unavailable"}`) or party-service returned no id on onboarding |
| *passthrough* | any other status/body returned verbatim from the upstream service |

## Events

**None.** The edge is a stateless proxy — it owns no outbox and publishes no domain events. Audit-relevant events are emitted by the upstream services it calls (account, payment, sca, party).

## Versioning

- **Customer API version in URL** (`/customer/v1`). The OpenAPI `info.version` (`1.6.0`) is the API-contract axis (ADR-0048), independent of the release `version.txt` (`0.9.0`).
- Upstream calls target each service's own `/api/v1`.
- **OpenAPI diff** in CI guards against breaking changes without a contract bump.

## Public mobile status copy

`GET /customer/v1/app-copy` is anonymous and returns only `{version, messages}` projected from the published customer communication style. It exposes no customer information or internal persona instructions. Successful published responses are cacheable for 60 seconds. An absent or retired publication returns version zero and an empty map, explicitly clearing client overrides.

The communication upstream is supplied through `COMMUNICATION_SERVICE_URL` at runtime. A missing configuration or unavailable upstream returns 503; an invalid upstream document returns 502. Clients retain validated cached or bundled copy on either error and must not delay startup waiting for this optional request. The upstream read uses the edge service identity without a customer party header.
