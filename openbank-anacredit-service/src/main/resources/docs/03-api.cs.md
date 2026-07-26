# API & kontrakty

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.0.3, `info.version` 0.1.1). Kontraktní test (`AnaCreditContractTest`) připíná `openapi.yaml:info.version` k `version.txt`.

## Základní cesta

- **Produkční base:** `http://openbank-anacredit-service:8137/api/v1` (v clusteru)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (nastaveno přes `quarkus.swagger-ui.path`)

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`). Resource je role-gated na úrovni třídy:

```
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE", "ROLE_API")
```

| Role | Typické použití |
|---|---|
| `ROLE_OPERATOR` | registrace expozic, vykreslení výkazů |
| `ROLE_API` | upstream feed posílající expozice |
| `ROLE_AUDITOR` | čtení výkazů + stopy vyloučení |
| `ROLE_COMPLIANCE` | regulatorní review |
| `ROLE_ADMIN` | vše |

Ve v1 není per-endpoint metodové gating — kterákoliv z uvedených rolí může volat kterýkoliv endpoint.

## Idempotence

Hlavička `Idempotency-Key` se nepoužívá. Registrace je **upsert klíčovaný `instrumentId`** — opětovné odeslání téhož nástroje nahradí jeho předchozí snapshot, takže operace je přirozeně idempotentní. Oba GET endpointy jsou čistá čtení.

## Endpointy

### Registrace / náhrada úvěrové expozice

```http
POST /api/v1/anacredit/exposures
Content-Type: application/json
Authorization: Bearer <token>

{
  "instrumentId": "od-0001",
  "debtorId": "lei-5493001KJTIIGC8Y1R12",
  "debtorType": "LEGAL_ENTITY",
  "instrumentType": "OVERDRAFT",
  "currency": "EUR",
  "committedAmount": 50000.00,
  "drawnAmount": 12000.00,
  "committedAmountEur": 50000.00,
  "arrearsAmount": 0,
  "defaulted": false,
  "originationDate": "2025-11-01"
}
```

```http
201 Created
Content-Type: application/json

{
  "instrumentId": "od-0001",
  "debtorId": "lei-5493001KJTIIGC8Y1R12",
  "debtorType": "LEGAL_ENTITY",
  "instrumentType": "OVERDRAFT",
  "currency": "EUR",
  "committedAmount": 50000.00,
  "drawnAmount": 12000.00,
  "offBalanceSheetAmount": 38000.00
}
```

- `instrumentType` má default `OVERDRAFT`, pokud chybí.
- `committedAmountEur` se používá **jen** pro práh €25 000; datový soubor reportuje částky v nativní `currency`.
- `offBalanceSheetAmount` v odpovědi je odvozené: `max(committedAmount − drawnAmount, 0)`.

### Výpis všech známých expozic

```http
GET /api/v1/anacredit/exposures
Authorization: Bearer <token>
```

```http
200 OK
[ { "instrumentId": "od-0001", ... } ]
```

Vrací každou uloženou expozici (seřazeno podle `instrumentId`), každou jako projekci `Exposure` (včetně odvozeného `offBalanceSheetAmount`).

### Vykreslení výkazu AnaCredit

```http
GET /api/v1/anacredit/returns/2026-05-31
Authorization: Bearer <token>
```

```http
200 OK
{
  "referenceDate": "2026-05-31",
  "reportableCount": 1,
  "excludedCount": 2,
  "records": [
    {
      "instrumentId": "od-0001",
      "debtorId": "lei-5493001KJTIIGC8Y1R12",
      "instrumentType": "OVERDRAFT",
      "currency": "EUR",
      "outstandingNominalAmount": 12000.00,
      "offBalanceSheetAmount": 38000.00,
      "arrearsAmount": 0,
      "defaultStatus": "NOT_IN_DEFAULT",
      "referenceDate": "2026-05-31"
    }
  ],
  "exclusions": [
    { "instrumentId": "od-0002", "debtorId": "person-1", "reason": "HOUSEHOLD_OUT_OF_SCOPE" },
    { "instrumentId": "od-0003", "debtorId": "lei-small",  "reason": "BELOW_THRESHOLD" }
  ]
}
```

- `referenceDate` je konec měsíce, k němuž se výkaz vykresluje (ISO `yyyy-MM-dd`, path parametr).
- Výkaz vždy nese **obojí** — reportovatelné `records` i auditní stopu `exclusions`.
- Důvodové kódy vyloučení: `HOUSEHOLD_OUT_OF_SCOPE`, `BELOW_THRESHOLD`, `NO_EXPOSURE`.

## Schémata (z openapi.yaml)

| Schéma | Role |
|---|---|
| `RegisterExposureRequest` | POST body — povinné: `instrumentId`, `debtorId`, `debtorType`, `currency`, `committedAmount`, `drawnAmount`, `committedAmountEur`, `originationDate` |
| `Exposure` | projekce expozice s odvozeným `offBalanceSheetAmount` |
| `CreditRecord` | jeden reportovatelný řádek úvěrového/finančního datového souboru |
| `Exclusion` | vyřazený nástroj s důvodovým kódem |
| `AnaCreditReturn` | `referenceDate`, `reportableCount`, `excludedCount`, `records[]`, `exclusions[]` |
| `CounterpartyType` | enum `LEGAL_ENTITY`, `NATURAL_PERSON` |
| `InstrumentType` | enum `OVERDRAFT`, `CREDIT_CARD_CREDIT`, `REVOLVING_CREDIT`, `LOAN` |

## Model chyb

Standardní odpovědi Quarkus / RESTEasy Reactive. Vadné `referenceDate` selže na `LocalDate.parse` (třída 400); selhání autentizace vrací 401/403 přes bezpečnostní vrstvu. v1 nedefinuje vlastní `ApiError` tělo pro tuto službu.

| HTTP | Kdy |
|---|---|
| 400 | neparsovatelné `referenceDate`, vadný JSON / enum |
| 401 | chybějící / neplatný bearer token |
| 403 | token nemá žádnou z povolených rolí |
| 201 | expozice uložena |
| 200 | seznam / výkaz vykreslen |

## Události

**Žádné.** anacredit-service je derive-only: neemituje žádné doménové události ani žádné nekonzumuje. Není zde žádné Kafka téma ani outbox.

## Zpětná kompatibilita

- **Verze API v URL** (`/api/v1/...`). Breaking změny ⇒ `/api/v2/...`.
- **Dvě verzovací osy (ADR-0048):** release verze (`version.txt`) a verze OpenAPI kontraktu (`openapi.yaml:info.version`) jsou nezávislé. `AnaCreditContractTest` vynucuje, že verze kontraktu zůstává v souladu dle pravidla kontraktní osy.
- **OpenAPI diff** v CI proti `main` — odstraněný endpoint nebo nově povinné pole musí přijít s odpovídajícím bumpem kontraktu.
