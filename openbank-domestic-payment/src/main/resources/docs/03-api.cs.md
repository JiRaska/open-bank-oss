# API & kontrakty

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.1.0`). Hlavní verze v URL (`/api/v1`) je osa API kontraktu (ADR-0048) a je nezávislá na release verzi služby (`version.txt`).

## Základní cesta

- **Báze v clusteru:** `http://openbank-domestic-payment:8116/api/v1`
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.path`, always-included)

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`, RS256 JWT). Role podle endpointu:

| Endpoint | Povolené role |
|---|---|
| `POST /domestic-payments` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /domestic-payments/{paymentId}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /domestic-payments` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API` |
| `PATCH /domestic-payments/{paymentId}/status` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` + `@Authorize(action="domesticPayment.transitionStatus")` (OPA, ADR-0034) |

OIDC je vypnuté pouze v profilech `%dev` a `%test`.

## Idempotence

`POST /domestic-payments` **vyžaduje** hlavičku:

```
Idempotency-Key: <klientem-generovaný-unikátní-klíč>
```

Pravidla:
- Prázdný klíč nebo klíč delší než 128 znaků je odmítnut (`400`).
- Postgres atomicky sváže klíč s SHA-256 normalizovaného příkazu a identity aktéra, spolu s platbou a outbox událostí.
- Přesný replay vrátí trvalou platbu s `X-Idempotency-Replayed: true`; změněný požadavek nebo starší řádek bez ověřitelného otisku vrátí `409 IDEMPOTENCY_KEY_REUSED` (`application/problem+json`). Redis není autoritou pro založení platby. U neověřitelného staršího řádku neopakujte požadavek s novým klíčem: první pokus mohl být uložen, takže výsledek musí určit lookup stavu platby / operátorská rekonciliace.

## Klíčové endpointy

### Založit tuzemskou platbu

```http
POST /api/v1/domestic-payments
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "debtorAccountId": "f0c1...-uuid",
  "debtorAccountNumber": "19-2000145399",
  "debtorBankCode": "0800",
  "debtorName": "Jan Novák",
  "creditorAccountNumber": "123456789",
  "creditorBankCode": "0100",
  "creditorName": "ČEZ a.s.",
  "amount": 1500.00,
  "currency": "CZK",
  "variableSymbol": "1234567890",
  "specificSymbol": null,
  "constantSymbol": "0308",
  "messageForPayee": "Faktura 2026-01",
  "priority": "STANDARD",
  "transferScope": "INTERNAL_CLIENT",
  "technicalAccountCode": null,
  "statementLabel": null,
  "endToEndId": null
}
```

```http
201 Created
Location: /api/v1/domestic-payments/{paymentId}
Content-Type: application/json

{
  "id": "…uuid…",
  "status": "VALIDATED",      // nebo RECEIVED (drženo k revizi / screening nedostupný) / REJECTED (sankční zásah)
  "debtorAccountId": "…",
  "creditorAccountNumber": "123456789",
  "creditorBankCode": "0100",
  "amount": 1500.00,
  "currency": "CZK",
  "endToEndId": "DOMS1716...",
  "rejectReason": null,
  "createdAt": "2026-06-09T10:42:13Z"
}
```

**Poznámky:**
- Vrácený `status` odráží výsledek synchronního screeningu (viz [02 — Architektura](./02-architecture.md)): `VALIDATED` (čisté), `RECEIVED` (drženo k revizi nebo screening nedostupný), nebo `REJECTED` s `rejectReason=SANCTIONS_HIT`.
- `endToEndId` se vygeneruje, pokud není dodán (`DOM<iniciála-priority><epoch-millis><rand>`).
- `transferScope=TECHNICAL_ACCOUNT` vyžaduje `technicalAccountCode`.
- Ve stejné transakci se zapíše outbox událost `domestic.payment.created`; pokud screening platbu přepne, následuje událost `domestic.payment.status-changed`.

### Získat platbu

```http
GET /api/v1/domestic-payments/{paymentId}
→ 200 (DomesticPaymentResponse) | 404 (ApiError NOT_FOUND)
```

### Vypsat platby

```http
GET /api/v1/domestic-payments?status=VALIDATED&debtorAccountId={uuid}&limit=50&offset=0
→ 200 [DomesticPaymentResponse]
```

`limit` je omezen do `1..200`; `offset` na `>= 0`.

### Přechod stavu

```http
PATCH /api/v1/domestic-payments/{paymentId}/status
{
  "targetStatus": "SENT_TO_CLEARING",
  "rejectReason": null,          // povinné, když targetStatus = REJECTED
  "rejectDetail": null
}
→ 200 (aktualizovaný DomesticPaymentResponse) | 404 | 409 (nelegální přechod)
```

Stavový automat (`DomesticPayment.canTransitionTo`):

```
RECEIVED         → VALIDATED | REJECTED | CANCELLED
VALIDATED        → SENT_TO_CLEARING | REJECTED | CANCELLED
SENT_TO_CLEARING → SETTLED | RETURNED | REJECTED
SETTLED / REJECTED / RETURNED / CANCELLED → (terminální)
```

`REJECTED` vyžaduje `rejectReason` (např. `SANCTIONS_HIT`, `AML_HOLD`, `INSUFFICIENT_FUNDS`, `AMOUNT_LIMIT_EXCEEDED`, …). `submittedAt` se razítkuje při prvním přechodu mimo `RECEIVED`; `settledAt` při `SETTLED`/`RETURNED`/`CANCELLED`.

## Chybový model

Chyby používají `ApiError` z `openbank-libs` (`{ correlationId, status, code, message }`):

| HTTP | code | Kdy |
|---|---|---|
| 400 | (validace) | chybějící `Idempotency-Key`, vadné tělo / enum |
| 401 | unauthorized | chybějící / neplatný token |
| 403 | forbidden | chybí role pro endpoint (nebo OPA deny v enforce módu) |
| 404 | `NOT_FOUND` | id platby neexistuje (`DomesticPaymentNotFoundException`) |
| 409 | `CONFLICT` | nelegální přechod stavu (`InvalidDomesticPaymentStateTransitionException`) |

## Události

Topic: `openbank.domestic.payment.events` (Kafka, JSON, kanál `events-out`).

| Typ události | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `domestic.payment.created` | `POST /domestic-payments` | paymentId, idempotencyKey, status, debtorAccountId, účet+banka plátce/příjemce, amount, currency, priority, endToEndId, occurredAt |
| `domestic.payment.status-changed` | jakýkoliv perzistovaný přechod | paymentId, previousStatus, newStatus, rejectReason, rejectDetail, occurredAt |

Události jsou **append-only**; opravy se dělají novou status-changed událostí, ne přepsáním.

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`). Breaking změna jde do `/api/v2/...`; bump klasifikuj z OpenAPI diffu (`oasdiff`), ne z typu commitu (ADR-0048).
- **Evoluce událostí:** aditivní (nová volitelná pole) na existujícím topicu; breaking změna znamená nový topic.
- **OpenAPI diff** je v CI hlídán proti `main`.
