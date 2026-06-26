# API & contracts

## Base path

- **App port:** `8107` (in-cluster `http://openbank-psd2-service:8107`), management port `8085` (root-path `/q`).
- **Open Banking base path:** `/open-banking/v2`
- **Sandbox base path:** `/open-banking/sandbox/v2`
- **OpenAPI spec:** [`src/main/resources/openapi.yaml`](../openapi.yaml) (`info.version: 2.0.0`)
- **Swagger UI:** `/open-banking/docs`

> **Contract drift to reconcile (not yet formalized):** the checked-in `openapi.yaml` describes header names `X-Consent-Id` / `X-Idempotency-Key` and a local server on port `8122`, whereas the JAX-RS resources actually read `Consent-ID` / `Idempotency-Key` and the app runs on `8107`. The resource code is authoritative for runtime behavior; the OpenAPI document and resources should be brought into sync (tracked as a follow-up). The descriptions below reflect the **code**.

## Authentication & authorization

PSD2 endpoints are **not** Keycloak-gated. TPP identity and role are enforced by `EidasMtlsFilter`:

| Step | Mechanism |
|---|---|
| TPP identity | eIDAS QWAC client certificate subject DN passed as `SSL-CLIENT-S-DN` (terminated at the gateway), **or** the `X-TPP-ID` header |
| Role check | `tpp-registry-service` `GET /api/v1/tpp-registry/check?tppId&role`; role = `PISP` for `/payments`, otherwise `AISP` |
| Per-resource access | `consent-service` `validateConsent(consentId, tppId, scope, iban)` on every AIS read and PIS initiation |

Outcomes from the filter:

| HTTP | `tppMessages.code` | When |
|---|---|---|
| 401 | `CERTIFICATE_MISSING` | no QWAC and no `X-TPP-ID` |
| 401 | `CERTIFICATE_INVALID` | TPP not authorized for the required role |
| 503 | `SERVICE_UNAVAILABLE` | tpp-registry unreachable / circuit open |

Sandbox paths skip the filter entirely.

## Idempotency

| Surface | Key source | Cache key | Store |
|---|---|---|---|
| PIS — payment initiation | `Idempotency-Key` header (required, must be non-blank) | `psd2:payment:{tppId}:{product}:{key}` | Redis, 24 h TTL |
| Consent creation | `X-Request-ID` header (required) | `psd2:consent:{tppId}:{requestId}` | Redis, 24 h TTL |

On a cache hit the original status code and body are replayed with the response header `X-Idempotency-Replayed: true`. TTL is configured by `openbank.psd2.idempotency-ttl-seconds` (default `86400`).

## Account Information Service (AIS)

```http
GET /open-banking/v2/accounts
Consent-ID: <consentId>
# TPP identity via QWAC (SSL-CLIENT-S-DN) or X-TPP-ID header
```

```http
GET /open-banking/v2/accounts/{accountId}/balances
Consent-ID: <consentId>
```

```http
GET /open-banking/v2/accounts/{accountId}/transactions
  ?dateFrom=2026-01-01&dateTo=2026-06-30
  &bookingStatus=BOOKED|PENDING|BOTH
  &limit=50&afterCursor=<cursor>
Consent-ID: <consentId>
```

Transactions are paged; when more results exist the response carries `_links.next.href = "?afterCursor=…"`. The booking status defaults to `BOOKED` and `limit` defaults to `50`. Each AIS call first validates consent for the matching scope (`ACCOUNTS_READ` / `BALANCES_READ` / `TRANSACTIONS_READ`); failure ⇒ `ConsentUnauthorizedException` → `401 CONSENT_INVALID`.

## Consent lifecycle

```http
POST /open-banking/v2/consents
X-Request-ID: <uuid>            # required
TPP-Redirect-URI: <uri>         # optional
TPP-Name: <name>                # optional, defaults to tppId

{ "access": { "accounts": [...], "balances": [...], "transactions": [...] },
  "recurringIndicator": true, "validUntil": "2026-09-01",
  "frequencyPerDay": 4, "combinedServiceIndicator": false }
```

```http
201 Created
Location: /open-banking/v2/consents/{consentId}
{ "consentId": "...", "consentStatus": "RECEIVED", "access": {...},
  "links": { "self": "...", "status": ".../status",
             "scaRedirect": ".../authorisations" } }
```

- Requested `validUntil` is capped to **90 days** from now.
- `access` is translated into internal scopes (`ACCOUNTS_READ`, `BALANCES_READ`, `TRANSACTIONS_READ`, plus ČOBS extensions `STANDING_ORDERS_READ`, `DIRECT_DEBITS_READ`).
- `GET /open-banking/v2/consents/{id}` and `GET .../{id}/status` return the consent / its status; `DELETE .../{id}` revokes it (`204 No Content`).
- Consent status is mapped from internal states: `ACTIVE→VALID`, `PENDING_SCA→RECEIVED`, `REVOKED→REVOKED_BY_PSU`, `EXPIRED→EXPIRED`, `REJECTED→REJECTED`.

## Payment Initiation Service (PIS)

```http
POST /open-banking/v2/payments/sepa-credit-transfers
POST /open-banking/v2/payments/instant-sepa-credit-transfers
POST /open-banking/v2/payments/domestic-cz
POST /open-banking/v2/payments/sipo
Consent-ID: <consentId>
Idempotency-Key: <uuid>         # required
```

Request body varies by product:

| Product | Body model | Notable fields |
|---|---|---|
| SEPA / instant SEPA | `PaymentInitiation` | `debtorAccount.iban`, `creditorAccount.iban`, `creditorName`, `instructedAmount {currency, amount}`, `endToEndIdentification`, `remittanceInformationUnstructured` |
| Domestic CZ | `DomesticCzPayment` | adds `variableSymbol` / `specificSymbol` / `constantSymbol` (joined as remittance) |
| SIPO | `SipoPayment` | `sipoNumber`, `variableSymbol`; creditor fixed to the SIPO collection account, amount resolved downstream |

```http
201 Created
{ "paymentId": "...", "transactionStatus": "RCVD", "scaStatus": "received",
  "links": { "self": "...", "status": ".../status" } }
```

Each initiation validates consent (scope `PAYMENTS_INITIATE`, or `DOMESTIC_PAYMENT_INITIATE` / `SIPO_PAYMENT_INITIATE`) then forwards to `transaction-service`. A missing debtor/creditor IBAN ⇒ `InvalidPaymentProductException` → `400 PRODUCT_INVALID`.

```http
GET /open-banking/v2/payments/{product}/{paymentId}/status
# product ∈ sepa-credit-transfers | instant-sepa-credit-transfers | domestic-cz | sipo
→ 200 { "transactionStatus": "RCVD|PDNG|ACTC|ACSC|RJCT|CANC" }
```

An unknown `product` value ⇒ `404`.

`transactionStatus` (ISO 20022) values: `RCVD` (received), `PDNG` (pending), `ACTC` (accepted technical validation), `ACSC` (accepted settlement completed), `RJCT` (rejected), `CANC` (cancelled).

## Sandbox

`/open-banking/sandbox/v2/{accounts,…/balances,…/transactions,consents,payments/{product}}` and `…/health` return deterministic fixtures (e.g. account `CZ6508000000192000145399`, balance `50000.00 CZK`) and bypass TPP authentication. Used by TPP developers during onboarding.

## Error model

Errors use the Open Banking / Berlin-Group `tppMessages` envelope (not the generic `openbank-libs` `ApiError`):

```json
{ "tppMessages": [ { "category": "ERROR", "code": "CONSENT_INVALID", "text": "..." } ] }
```

| HTTP | code | When (exception) |
|---|---|---|
| 400 | `FORMAT_ERROR` | `IllegalArgumentException` (e.g. blank required header) |
| 400 | `PRODUCT_INVALID` | `InvalidPaymentProductException` |
| 401 | `CERTIFICATE_MISSING` | no TPP identity (filter) |
| 401 | `CERTIFICATE_INVALID` | `TppNotAuthorizedException` / role rejected |
| 401 | `CONSENT_INVALID` | `ConsentUnauthorizedException` |
| 404 | `CONSENT_UNKNOWN` | `ConsentNotFoundException` |
| 404 | — | unknown payment product on status lookup |
| 503 | `SERVICE_UNAVAILABLE` | tpp-registry circuit open / unreachable |

## Versioning

- **API version in the path** — `/open-banking/v2/...`. The OpenAPI `info.version` is `2.0.0`.
- **Event version in the topic** — `openbank.psd2.events` (additive evolution; breaking change ⇒ new topic).
- The two version axes (release `version.txt` vs API `openapi.yaml:info.version`) are independent (ADR-0048).
