# API & kontrakty

## Base path

- **In-cluster base:** `http://openbank-product-catalog:8104/api/v1`
- **Lokální dev:** `http://localhost:8104/api/v1`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8104/q/openapi) (zdroj pravdy: `openapi.yaml`, `info.version` 0.1.0, OpenAPI 3.1.0)
- **Swagger UI:** `/api/docs` (nastaveno přes `quarkus.swagger-ui.path`, `always-include: true`)

Major z `openapi.yaml:info.version` se rovná `openbank.api.version` a rovná se URL `/api/v1` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)). Release verze (`version.txt`) je samostatná osa.

## Autentizace

V kódu dnes **není zapojená žádná autentizace na úrovni služby** — resources nejsou anotované `@RolesAllowed` a na classpath není žádná Keycloak/OIDC extenze. Služba se spoléhá na:

- API gateway / hranici sítě v clusteru pro řízení přístupu a
- CORS + bezpečnostní response hlavičky nastavené v `application.yaml` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`, HSTS, `Permissions-Policy`, `Referrer-Policy`).

CORS origins jsou omezeny na `http://localhost:3000` a `http://openbank-admin-ui:3000`. Přidání rolí chráněných mutací (např. `ROLE_PRODUCT_ADMIN` pro create/update/activate) je rozumný hardening follow-up — viz [06 — Compliance](./06-compliance.md).

## Idempotence

Mechanismus `Idempotency-Key` není implementován. Create je přirozeně chráněn **unikátním `code` produktu**: duplicitní code vrací `409 Conflict`. Úpravy a přechody životního cyklu probíhají podle `id` a jsou fakticky idempotentní (opakování activate dá stejný stav ACTIVE).

## Endpointy

### Produkty

| Metoda | Cesta | Účel | Úspěch | Chyby |
|---|---|---|---|---|
| GET | `/api/v1/products` | Výpis produktů; volitelné `?type=&status=&currency=` | 200 pole | — |
| GET | `/api/v1/products/{id}` | Detail produktu | 200 | 404 |
| POST | `/api/v1/products` | Vytvoření produktu (`ProductRequest`) | 201 | 409 duplicitní code |
| PUT | `/api/v1/products/{id}` | Úprava produktu (`ProductRequest`) | 200 | 404 |
| POST | `/api/v1/products/{id}/activate` | Nastaví status ACTIVE | 200 | 404 |
| POST | `/api/v1/products/{id}/deactivate` | Nastaví status INACTIVE | 200 | 404 |
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

{
  "id": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
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
    "id": "prod-003:<feeId>",
    "code": "CURRENT_PERSONAL_FX_CONVERSION",
    "name": "FX Conversion",
    "type": "TRANSACTION",
    "amount": 1.5,
    "currency": "EUR",
    "frequency": "PERCENTAGE",
    "productId": "prod-003",
    "productCode": "CURRENT_PERSONAL",
    "productName": "Personal Current Account",
    "status": "ACTIVE",
    "updatedAt": "2024-11-01T09:00:00Z"
  }
]
```

## Chybový model

Chyby se vrací jako minimální JSON objekt `{ "error": "<zpráva>" }` s odpovídajícím HTTP statusem (`404` nenalezeno, `409` duplicitní code). To je schéma `Error` v `openapi.yaml`. Sdílená obálka RFC-7807 problem+json zde zatím není zapojena — sjednocení na platformní chybovou obálku je follow-up.

## Verzování

- Verzování v URL: `/api/v1`.
- Verze OpenAPI kontraktu (`openapi.yaml:info.version`) je osa API kontraktu; jakákoli breaking změna ji musí zvednout dle klasifikace `oasdiff` a aktualizovat kontrakt + kontraktní test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
