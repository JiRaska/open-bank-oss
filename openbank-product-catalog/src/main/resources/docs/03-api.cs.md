# API & kontrakty

## Base path

- **In-cluster base:** `http://openbank-product-catalog:8104/api/v1`
- **Lokální dev:** `http://localhost:8104/api/v1`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8085/q/openapi) na management portu (zdroj pravdy: `openapi.yaml`, `info.version` 1.1.0, OpenAPI 3.0.3)
- **Swagger UI:** `http://localhost:8085/api/docs` (nastaveno přes `quarkus.swagger-ui.path`, `always-include: true`)

Major z `openapi.yaml:info.version` se rovná `openbank.api.version` a rovná se URL `/api/v1` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)). Release verze (`version.txt`) je samostatná osa.

## Autentizace

Služba je OIDC resource server. Čtení vyžaduje autentizovaný bearer token; create/update/lifecycle mutace vyžadují `ROLE_OPERATOR` nebo `ROLE_ADMIN`. `@Authorize` přidává policy action, ale OPA je advisory, dokud bankovní deployment nezapne vynucující profil. Prohlížeč stále používá autentizovaný BFF (ADR-0056).

CORS omezuje origins na nakonfigurované admin UI, zpřístupňuje `ETag` a povoluje `If-Match` pro optimistické zápisy.

## Idempotence

Mechanismus `Idempotency-Key` není implementován. Create chrání unikátní `code`. Čtení jednoho produktu a mutace vracejí číselný `ETag`; jeho odeslání přes `If-Match` (nebo `revision` v legacy body) odmítne zastaralého zapisujícího pomocí `409 Conflict`. Ve v1 je precondition kvůli kompatibilitě volitelná, takže neversionovaný legacy read-modify-write chráněn není; v2 ji vyžaduje.

## Endpointy

### Produkty

| Metoda | Cesta | Účel | Úspěch | Chyby |
|---|---|---|---|---|
| GET | `/api/v1/products` | Výpis produktů; volitelné `?type=&status=&currency=` | 200 pole | — |
| GET | `/api/v1/products/{id}` | Detail produktu | 200 | 404 |
| POST | `/api/v1/products` | Vytvoření produktu (`ProductRequest`) | 201 + ETag | 400 validace, 409 duplicitní code |
| PUT | `/api/v1/products/{id}` | Úprava produktu (`ProductRequest`, volitelné `If-Match`) | 200 + ETag | 400, 404, 409 stará revize |
| POST | `/api/v1/products/{id}/activate` | Legální přechod do ACTIVE | 200 + ETag | 404, 409, 422 |
| POST | `/api/v1/products/{id}/deactivate` | Legální přechod do INACTIVE | 200 + ETag | 404, 409, 422 |
| GET | `/api/v1/products/{id}/fees` | Poplatky jednoho produktu | 200 pole | 404 |

### Poplatky

| Metoda | Cesta | Účel | Úspěch |
|---|---|---|---|
| GET | `/api/v1/fees` | Celobankovní zploštělý sazebník; volitelné `?type=&currency=&productCode=` | 200 pole |

### Příklad — vytvoření produktu

```http
POST /api/v1/products
Content-Type: application/json

{
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "baseRate": 0.03,
  "fees": [
    { "name": "Account Maintenance", "type": "MONTHLY", "amount": 0.0,
      "currency": "EUR", "frequency": "MONTHLY", "waivable": false }
  ]
}
```

```http
201 Created
Content-Type: application/json
ETag: "0"

{
  "id": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "revision": 0,
  "fees": [ … ],
  "updatedAt": "2026-06-09T10:00:00Z"
}
```

Na `ProductRequest` jsou povinné jen `code`, `name`, `type`, `currency`; bohaté konfigurační bloky (`cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig`) jsou volitelné. Při create neuvedený `status` defaultuje na `DRAFT` a `isPublic` na `true`.

### Příklad — položka sazebníku

```http
GET /api/v1/fees?productCode=CURRENT_PERSONAL
```

```json
[
  {
    "id": "<canonical-product-uuid>:<feeId>",
    "code": "CURRENT_PERSONAL_FX_CONVERSION",
    "name": "FX Conversion",
    "type": "TRANSACTION",
    "amount": 1.5,
    "currency": "EUR",
    "frequency": "PERCENTAGE",
    "productId": "<canonical-product-uuid>",
    "productCode": "CURRENT_PERSONAL",
    "productName": "Personal Current Account",
    "status": "ACTIVE",
    "updatedAt": "2024-11-01T09:00:00Z"
  }
]
```

## Chybový model

Legacy 404 odpovědi zachovávají `{ "error": "<zpráva>" }` kvůli existujícímu Paktu. Validační, lifecycle a konfliktní chyby používají fleet obálku `ApiError` s `traceId`, `status`, `code`, `message` a `timestamp`.

## Verzování

- Verzování v URL: `/api/v1`.
- Verze OpenAPI kontraktu (`openapi.yaml:info.version`) je osa API kontraktu; jakákoli breaking změna ji musí zvednout dle klasifikace `oasdiff` a aktualizovat kontrakt + kontraktní test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
