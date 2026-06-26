# API

REST kontrakt z `src/main/resources/openapi.yaml` (`OpenAPI 3.1.0`, `info.version 1.0.0`). URL major odpovídá `openbank.api.version = 1` → všechny cesty jsou pod `/api/v1` (osa API kontraktu je nezávislá na release `version.txt`, dle [ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).

Base URL (lokální dev): `http://localhost:8122`. JSON in/out (`application/json`).

## Endpointy

| Metoda | Cesta | Operace | Účel |
|---|---|---|---|
| `POST` | `/api/v1/swift` | `sendSwiftMessage` | Odeslat SWIFT zprávu k dispatchi (idempotentní) |
| `GET` | `/api/v1/swift/{id}` | `getSwiftMessage` | Získat zprávu podle UUID |
| `GET` | `/api/v1/swift/status/{status}` | `listSwiftByStatus` | Vypsat zprávy podle stavu životního cyklu |
| `POST` | `/api/v1/swift/{id}/ack` | `acknowledgeSwiftMessage` | Zaznamenat ACK od přijímající banky → `ACKNOWLEDGED` |
| `POST` | `/api/v1/swift/{id}/reject` | `rejectSwiftMessage` | Zaznamenat zamítnutí → `REJECTED` |
| `GET` | `/api/v1/swift/messages` | (jen v resource) | Vypsat všechny zprávy — **přítomno v `SwiftResource`, ale ne v `openapi.yaml`** (drift kontraktu, viz poznámka) |

> **Drift kontraktu k narovnání:** `SwiftResource.listAll()` vystavuje `GET /api/v1/swift/messages`, který není popsán v `openapi.yaml`. OpenAPI soubor by měl být aktualizován o tento endpoint (nebo endpoint odstraněn), aby kontraktní test zůstal zelený. Označeno jako TBD.

## Submit — `POST /api/v1/swift`

Tělo požadavku `SendSwiftCommand` (povinná pole): `idempotencyKey`, `messageType`, `senderBic`, `receiverBic`, `transactionReference`, `valueDate`, `currency`, `amountMinorUnits`, `beneficiaryAccount`, `beneficiaryName`. Volitelná: `relatedReference`, `orderingCustomerAccount`, `orderingCustomerName`, `remittanceInfo`, `chargeCode` (výchozí `SHA`), `priority` (výchozí `NORMAL`).

Omezení polí (ze schématu): BIC odpovídá `^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$` (8 nebo 11 znaků); `transactionReference` ≤ 16 (SWIFT pole 20); `relatedReference` ≤ 16 (pole 21); `valueDate` je `^\d{8}$` (YYYYMMDD); `currency` je ISO 4217 `^[A-Z]{3}$`; `amountMinorUnits` ≥ 1; `remittanceInfo` ≤ 140 (pole 70); `chargeCode` ∈ {OUR, SHA, BEN} (pole 71A).

Příklad (MT103 zákaznický úhradový převod):

```json
{
  "idempotencyKey": "pay-2024-001",
  "messageType": "MT103",
  "senderBic": "KOMBCZPP",
  "receiverBic": "DEUTDEDB",
  "transactionReference": "TXN20240101001",
  "valueDate": "20240101",
  "currency": "EUR",
  "amountMinorUnits": 150000,
  "orderingCustomerAccount": "CZ6508000000192000145399",
  "orderingCustomerName": "Jan Novák",
  "beneficiaryAccount": "DE89370400440532013000",
  "beneficiaryName": "Hans Müller",
  "remittanceInfo": "Invoice INV-2024-001",
  "chargeCode": "SHA",
  "priority": "NORMAL"
}
```

Odpovědi:

| Kód | Význam |
|---|---|
| `201` | Přijato a zařazeno; vrací `SwiftMessage` (stav `VALIDATED`) |
| `400` | Neplatné tělo požadavku (`ErrorResponse`) |
| `409` | Duplicitní idempotency key s konfliktním payloadem (`ErrorResponse`) |
| `422` | Selhání validace BIC nebo porušení business pravidla (`ValidationError`) |

## Idempotence

Idempotence je na **úrovni payloadu**, ne hlavičky: klient dodává `idempotencyKey` v `SendSwiftCommand`. `SwiftService.send` nejprve volá `findByIdempotencyKey` a vrátí stávající zprávu beze změny, pokud existuje; DB navíc vynucuje `UNIQUE` constraint na `idempotency_key`. Opakovaný submit se stejným klíčem vrátí původní zprávu bez znovuodeslání.

> Poznámka: platformní HTTP hlavička `Idempotency-Key` (povolená v CORS konfiguraci) a Redis klient jsou do služby zapojeny, ale send cesta se řídí polem z těla.

## Acknowledge / Reject

- `POST /api/v1/swift/{id}/ack` — tělo `{ "ackRef": "ACKREF…" }`. Přepne zprávu na `ACKNOWLEDGED` a nastaví `ackReceivedAt`. Chráněno `@Authorize(action = "swift.acknowledge", resource = "#id")` (OPA, ADR-0034).
- `POST /api/v1/swift/{id}/reject` — tělo `{ "reason": "…" }`. Přepne na `REJECTED` a zaznamená `rejectionReason`.

Oba vracejí aktualizovanou `SwiftMessage`; `404` při neznámém id; `409`, pokud zpráva není ve stavu vhodném pro ack/reject (dle OpenAPI).

## Dotaz na stav — `GET /api/v1/swift/status/{status}`

`{status}` ∈ `PENDING | VALIDATED | SENT | ACKNOWLEDGED | REJECTED | FAILED`. Vrací pole `SwiftMessage`.

## Model chyb

```json
{ "error": "string", "message": "string", "traceId": "string" }
```

Validační chyby používají `ValidationError`:

```json
{ "error": "Validation failed", "violations": [ { "field": "senderBic", "message": "..." } ] }
```

## Verzování

- **URL major** `/api/v1` ← `openbank.api.version`.
- **Verze API kontraktu** `openapi.yaml: info.version = 1.0.0` — bumpováno z OpenAPI diffu (`oasdiff`), nezávisle na release `version.txt` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`.

## Management / podpůrné endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/docs` (Swagger UI) | 8122 | `always-include: true` |
| `/q/openapi` | 8085 | OpenAPI dokument |
| `/q/openbank/docs` | 8085 | Docs-as-Service (tato dokumentace) |
| `/q/health` | 8085 | SmallRye Health (liveness/readiness) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |
