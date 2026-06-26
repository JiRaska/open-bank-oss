# API & kontrakty

## Base path

- **Produkční base:** `http://openbank-sanctions-service:8123/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8123/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8123/q/swagger-ui)

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** s realm `openbank`. Mutující operace navíc vyžadují `ROLE_OPERATOR`:

| Role | Oprávnění |
|---|---|
| `ROLE_VIEWER` | Pouze GET (seznam prověření, hitů, čekajících, listin) |
| `ROLE_OPERATOR` | GET + screen + review + správa listin |
| `ROLE_COMPLIANCE` | GET + screen + review (primární role pro compliance důstojníky) |
| `ROLE_ADMIN` | vše |

## Idempotence

Všechny **POST** screeningové požadavky vyžadují `idempotencyKey` v těle požadavku (ne v headeru jako u jiných služeb):

```json
{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  ...
}
```

Pravidla:
- Klient generuje UUID v4 pro každý logický screeningový požadavek.
- Stejný klíč → vrátí původní výsledek `SanctionsCheck` (replay-safe, cache v Redis).
- Klíč je uložen v `sanctions_checks.idempotency_key` (UNIQUE constraint).

## Klíčové endpointy

### Prověřit entitu

```http
POST /api/v1/sanctions/screen
Content-Type: application/json
Authorization: Bearer <token>

{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "entityType": "INDIVIDUAL",
  "name": "Jan Novák",
  "aliases": ["J. Novák"],
  "dateOfBirth": "1975-03-14",
  "nationality": "CZ",
  "identifiers": {
    "passport": "123456789"
  }
}
```

**Rozhodovací logika pro volající:**
- `CLEAR` nebo `WHITELISTED` → pokračuj s platbou/operací na účtu
- `POTENTIAL_HIT` → blokuj do ručního compliance přezkumu
- `HIT` nebo `ESCALATED` → blokuj; nepokračuj

### Odeslat rozhodnutí o ručním přezkumu

```http
POST /api/v1/sanctions/review
Content-Type: application/json
Authorization: Bearer <token>

{
  "checkId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reviewedBy": "compliance@openbank.example",
  "note": "Jiná osoba potvrzena kontrolou pasu — čisto.",
  "newStatus": "CLEAR"
}
```

`newStatus` ∈ `CLEAR | HIT | POTENTIAL_HIT | WHITELISTED | ESCALATED`

### Získat konkrétní prověření

```http
GET /api/v1/sanctions/{id}
```

### Zobrazit všechny potvrzené hity

```http
GET /api/v1/sanctions/hits
```

### Zobrazit čekající přezkumy

```http
GET /api/v1/sanctions/pending
```

### Zobrazit konfigurace sankcičních listin

```http
GET /api/v1/sanctions/lists
```

### Aktualizovat konfiguraci sankční listiny

```http
PUT /api/v1/sanctions/lists/{id}
Content-Type: application/json

{
  "enabled": true,
  "sourceUrl": "https://www.treasury.gov/ofac/downloads/sdn.xml",
  "cronHour": 6,
  "cronMinute": 0,
  "cronDays": "MON,TUE,WED,THU,FRI"
}
```

### Spustit manuální obnovu listiny

```http
POST /api/v1/sanctions/lists/{listType}/refresh
```

### Obnovit všechny povolené listiny

```http
POST /api/v1/sanctions/lists/refresh-all
```

## Typy entit

| EntityType | Popis |
|---|---|
| `INDIVIDUAL` | Fyzická osoba |
| `ORGANIZATION` | Právnická osoba, společnost |
| `VESSEL` | Loď nebo námořní plavidlo |
| `AIRCRAFT` | Letadlo dle čísla ocasu nebo označení ICAO |

## Typy sankcičních listin

| ListType | Autorita | Rozsah |
|---|---|---|
| `OFAC_SDN` | US Treasury OFAC | Specially Designated Nationals — globální |
| `EU_CONSOLIDATED` | EU Rada | Konsolidovaný seznam sankcí EU |
| `UN_CONSOLIDATED` | Rada bezpečnosti OSN | Konsolidovaný seznam OSN |
| `HM_TREASURY` | UK HM Treasury | UK finanční sankce |
| `FATF_HIGH_RISK` | FATF | Vysoce rizikové a sledované jurisdikce |
| `CNB_DOMESTIC` | Česká národní banka | Domácí české sankce |

## Error model

| HTTP | kód | Kdy |
|---|---|---|
| 400 | `validation-failed` | Chybí povinná pole (idempotencyKey, name, entityType) |
| 401 | `unauthorized` | Chybí / neplatný Keycloak token |
| 403 | `forbidden` | Chybí role pro endpoint |
| 404 | `sanctions-check-not-found` | ID prověření neexistuje |
| 404 | `sanctions-list-not-found` | ID listiny neexistuje |
| 409 | `idempotency-key-conflict` | Stejný klíč, jiný payload |
| 500 | `internal-error` | Neočekávaná chyba |

## Eventy

Topic: `openbank.sanctions.screening.event` (CloudEvents binding, JSON).

| Typ eventu | Spuštění | Klíčová pole payloadu |
|---|---|---|
| `sanctions.check.completed.v1` | POST /screen | id, entityType, name, status, overallScore, checkedLists, checkedAt |
| `sanctions.review.submitted.v1` | POST /review | checkId, reviewedBy, newStatus, reviewNote, reviewedAt |

Eventy jsou append-only; následný přezkum vytváří nový event `sanctions.review.submitted.v1`, nepřepisuje původní event prověření.
