# API a kontrakty

REST kontrakt je publikován jako OpenAPI ([`src/main/resources/openapi.yaml`](../openapi.yaml), `info.version: 1.1.0`) a servírován za běhu. URL major (`/api/v1`) je svázán s `openbank.api.version = 1` (ADR-0048). Tvary endpointů níže vycházejí z `SepaPaymentResource` a DTO.

> **Poznámka ke kontraktu:** přiložený `openapi.yaml` je na pár místech mírně pozadu za kódem (uvádí hodnoty stavového enumu `PENDING/RECALLED` a tělo chyby `code/message` a zastaralý lokální server port `8102`). Autoritativní chování je to, co implementují `SepaPaymentResource`, `SepaPaymentDtos` a `ExceptionMappers`, popsané zde. Sladění specifikace je vedeno jako follow-up.

## Základní cesta

- **Port aplikace:** `8115`; **management port:** `8085` (root-path `/q`)
- **Základní cesta:** `/api/v1/sepa-payments`
- **OpenAPI spec:** `/q/openapi` · **Swagger UI:** `/api/docs` (konfig `quarkus.swagger-ui.path`)

## Autentizace a autorizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`). Role jsou gateovány per endpoint:

| Endpoint | Povolené role |
|---|---|
| `POST /sepa-payments` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /sepa-payments/{id}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /sepa-payments` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API` |
| `PATCH /sepa-payments/{id}/status` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |

Stavový přechod je navíc střežen OPA přes `@Authorize(action = "sepaPayment.transitionStatus", resource = "#paymentId")` — defaultně advisory, vynucováno při `AUTHZ_ENFORCE=true` (ADR-0034).

## Idempotence

`POST /sepa-payments` **vyžaduje** hlavičku:

```
Idempotency-Key: <klientem-generované-uuid>
```

Pravidla:
- Prázdný klíč je odmítnut (`400`).
- Při zásahu v cache je dříve uložená odpověď přehrána s hlavičkou `X-Idempotency-Replayed: true` (Redis `IdempotencyStore`).
- Use-case navíc deduplikuje podle `idempotency_key` (UNIQUE v DB) a vrací existující platbu, takže retry nikdy nevytvoří duplicitní převod.

## Endpointy

### Vytvoření SEPA platby

```http
POST /api/v1/sepa-payments
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "type": "SCT",
  "debtorAccountId": "11111111-1111-1111-1111-111111111111",
  "debtorIban": "CZ6508000000192000145399",
  "debtorName": "Alice Debtor",
  "creditorIban": "DE89370400440532013000",
  "creditorName": "Bob Creditor",
  "creditorBic": "COBADEFFXXX",
  "amount": 1250.00,
  "currency": "EUR",
  "remittanceInfo": "Faktura 2026-0042",
  "endToEndId": "E2E-2026-0042"
}
```

```http
201 Created
Location: /api/v1/sepa-payments/<paymentId>

{
  "id": "…",
  "type": "SCT",
  "status": "VALIDATED",            // nebo RECEIVED (drženo) / REJECTED (sankční zásah)
  "debtorAccountId": "…",
  "creditorIban": "DE89370400440532013000",
  "amount": 1250.00,
  "currency": "EUR",
  "endToEndId": "E2E-2026-0042",
  "rejectReason": null,
  "createdAt": "2026-06-09T10:42:13Z",
  "updatedAt": "2026-06-09T10:42:13Z"
}
```

**Finální stav odráží synchronní verdikt screeningu** (ADR-0032): `VALIDATED` (čistý), `RECEIVED` (drženo k přezkoumání nebo při výpadku screeningu) nebo `REJECTED` s `rejectReason=SANCTIONS_HIT`. `endToEndId` se generuje, pokud je vynecháno.

**Side-effecty:** řádek `sepa_payment_outbox` s `sepa.payment.created` (a `sepa.payment.status-changed`, když verdikt změní stav); AML případ otevřený v `aml-service` při zásahu/zadržení.

### Získání platby

```http
GET /api/v1/sepa-payments/{paymentId}
→ 200 OK SepaPaymentResponse  |  404 pokud nenalezeno
```

### Výpis plateb

```http
GET /api/v1/sepa-payments?status=VALIDATED&debtorAccountId=…&limit=50&offset=0
→ 200 OK [SepaPaymentResponse]
```

`limit` je ořezán na 1..200, `offset` na ≥ 0. `status` přijímá doménové hodnoty níže.

### Přechod stavu

```http
PATCH /api/v1/sepa-payments/{paymentId}/status
{ "targetStatus": "PROCESSING", "rejectReason": null, "rejectDetail": null }
→ 200 OK SepaPaymentResponse  |  409 při neplatném přechodu
```

`REJECTED` vyžaduje `rejectReason`.

## Stavový model

```
RECEIVED ──► VALIDATED ──► PROCESSING ──► COMPLETED
   │             │              │
   ├─► REJECTED  ├─► REJECTED   ├─► REJECTED
   ├─► CANCELLED ├─► CANCELLED  └─► RETURNED
   │
(COMPLETED / REJECTED / RETURNED / CANCELLED jsou terminální)
```

`SepaRejectReason` ∈ `INSUFFICIENT_FUNDS · INVALID_IBAN · ACCOUNT_CLOSED · ACCOUNT_FROZEN · INVALID_BIC · AMOUNT_LIMIT_EXCEEDED · AML_HOLD · SANCTIONS_HIT · TECHNICAL_ERROR`.

## Model chyb

Sjednoceno přes `openbank-libs` `ApiError` (`correlationId`, `status`, `code`, `message`):

| HTTP | code | Kdy |
|---|---|---|
| 400 | (bad request) | prázdný `Idempotency-Key`, neplatná hodnota enumu |
| 401 | unauthorized | chybějící / neplatný token |
| 403 | forbidden | chybí role, nebo OPA deny (enforce mód) |
| 404 | `NOT_FOUND` | id platby neexistuje (`SepaPaymentNotFoundMapper`) |
| 409 | `CONFLICT` | neplatný stavový přechod (`InvalidSepaPaymentStateTransitionMapper`) |

## Události

Topic: `openbank.sepa.payment.events` (JSON, vyprazdňováno z outboxu).

| Typ události | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `sepa.payment.created` | `POST /sepa-payments` | paymentId, idempotencyKey, type, status, debtorAccountId, debtorIban, creditorIban, amount, currency, endToEndId, occurredAt |
| `sepa.payment.status-changed` | verdikt screeningu nebo `PATCH …/status` | paymentId, previousStatus, newStatus, rejectReason, rejectDetail, occurredAt |

Události jsou append-only; korekce se provádějí kompenzačním stavovým přechodem, ne přepisem.

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`); breaking změna ⇒ `/api/v2/...` (ADR-0048, klasifikováno z `oasdiff`).
- **Evoluce událostí** je aditivní (jen volitelná pole); breaking změna ⇒ nový topic.
