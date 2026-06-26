# API

## Kontrakt a verzování

- **Základní cesta:** `/api/v1/parties` — všechny endpointy žijí pod API verzí `v1` (`openbank.api.version = "1"`, ADR-0048).
- **Media type:** `application/json` (consumes + produces).
- **Autentizace:** `Authorization: Bearer <JWT>` (Keycloak OIDC, RS256). Každý endpoint je chráněn `@RolesAllowed`; `changeStatus` je navíc hlídán přes OPA `@Authorize(action = "party.changeStatus", resource = "#id")` (defaultně advisory, ADR-0034).
- **OpenAPI:** servírováno na `/q/openapi`; v repu je `openapi.yaml`, ale je **částečně rozsynchronizovaný s kódem** (předchází endpointu case-lifecycle, používá jiná request DTO a zastaralý lokální port/pojmenování rolí). Níže uvedená tabulka endpointů je generována ze skutečné třídy `PartyResource` a je autoritativní. Sladění `openapi.yaml` se zdrojem je evidovaný follow-up.

> **Idempotence:** služba dnes **nemá hlavičku / cache `Idempotency-Key`** (na rozdíl od account-service). Vytvoření je deduplikováno na datové vrstvě: party s existujícím bankID `sub` vyhodí `PartyAlreadyExistsException` → **409** a unikátní constraint `(id_type, id_value)` na `party_external_ids` blokuje duplicitní externí id. Přidání edge idempotence je možným vylepšením.

## Endpointy

| Metoda | Cesta | Role | Účel | Úspěch |
|---|---|---|---|---|
| `POST` | `/api/v1/parties` | employee, admin | Vytvoření party (sjednocená identita) | `201` + `PartyResponse`, hlavička `Location` |
| `GET` | `/api/v1/parties/{id}` | employee, admin, customer | Získání party podle interního UUID | `200` + `PartyResponse` |
| `GET` | `/api/v1/parties/by-external-id?type=&value=` | employee, admin | Rozlišení party podle externího id (`type`=`ExternalIdType`) | `200` + `PartyResponse` |
| `GET` | `/api/v1/parties?givenName=&familyName=&email=&role=&status=&limit=20&afterId=` | employee, admin | Vyhledání / keyset stránkování party | `200` + `PartyResponse[]` |
| `POST` | `/api/v1/parties/{id}/sync/bankid` | employee, admin | Přepis core + kontaktních atributů z bankID | `200` + `PartyResponse` |
| `POST` | `/api/v1/parties/{id}/sync/rob` | employee, admin | Synchronizace adresy + AIFO z ROB | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/contact` | employee, admin, customer | Aktualizace email / telefon / jazyk / datová schránka | `200` + `PartyResponse` |
| `PUT` | `/api/v1/parties/{id}/kyc` | employee, admin | Nastavení KYC úrovně + AML rizika + PEP/sankce | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/status` | admin | Změna stavu (+ OPA `@Authorize`) | `200` + `PartyResponse` |
| `PATCH` | `/api/v1/parties/{id}/case` | employee, admin | Přechod PID verifikačního případu | `200` + `PartyResponse` |
| `POST` | `/api/v1/parties/{id}/relationships` | employee, admin | Přidání role/vztahu | `201` + `RelationshipResponse`, hlavička `Location` |
| `DELETE` | `/api/v1/parties/{id}/relationships/{relationshipId}` | employee, admin | Ukončení vztahu | `200` + `RelationshipResponse` |

## Klíčové tvary request/response

### `CreatePartyRequest` (POST /parties)

```json
{
  "partyType": "NATURAL_PERSON",        // NATURAL_PERSON | LEGAL_ENTITY | SOLE_TRADER (default NATURAL_PERSON)
  "givenName": "Jan",
  "familyName": "Novák",
  "birthdate": "1985-04-12",
  "nationalities": ["CZ"],
  "verificationSource": "BANKID",       // BANKID | BRANCH_MANUAL | API_UPLOAD | ROB (default BANKID)
  "bankIdSub": "bankid|abc123",         // volitelné; pokud je vyplněno, musí být unikátní
  "initialRole": "CUSTOMER",            // CUSTOMER | EMPLOYEE | ADMIN | AGENT | GUARANTOR | AUTHORIZED_PERSON
  "onboardingChannel": "BANKID"         // BANKID | BRANCH | API | MOBILE_APP
}
```

Při vytvoření služba nastaví `status=ACTIVE`, `kycLevel=BASIC`, `amlRiskScore=LOW`, otevře případ `PID_VERIFICATION` (`status=OPEN`) a vyšle `PartyCreated`, `case.created`, `RelationshipAdded`.

### `PartyResponse` (vracen všude)

Vnořený objekt s `id`, `partyType`, `status`, `externalIds[]`, `coreAttributes`, `addressAttributes?`, `contactAttributes`, `kycAttributes`, `relationships[]`, `caseLifecycle?`, `createdAt`, `updatedAt`, `version`. Plný seznam polí viz `dto/PartyDtos.kt`. Pozn.: šifrované rodné číslo se do `coreAttributes` **nikdy** neserializuje.

### `UpdateKycRequest` (PUT /kyc)

```json
{ "kycLevel": "ENHANCED", "amlRiskScore": "MEDIUM", "pepFlag": false, "sanctionsFlag": false }
```

### `TransitionCaseRequest` (PATCH /case)

```json
{ "status": "IN_REVIEW", "actor": "ops:alice", "reasonCode": "REVIEW_STARTED", "reason": "manuální KYC", "metadata": {} }
```

`status` ∈ `CaseStatus` (DRAFT, OPEN, IN_REVIEW, WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, APPROVED, REJECTED, CLOSED, CANCELLED); `reasonCode` ∈ `CaseReasonCode`. Nelegální přechody zamítá `CaseTransitionEngine`.

## Chybový model

Chyby používají jednotné tělo `ApiError` z `openbank-libs`:

```json
{ "traceId": "f1c2…", "status": 404, "code": "NOT_FOUND", "message": "Party … not found" }
```

| HTTP | `code` | Kdy | Zdroj |
|---|---|---|---|
| `400` | `VALIDATION_ERROR` | nelegální přechod PID case | `InvalidPartyCaseTransitionMapper` |
| `404` | `NOT_FOUND` | party / vztah / externí id nenalezeno | `PartyNotFoundMapper` |
| `409` | `CONFLICT` | duplicitní bankID sub při vytvoření | `PartyAlreadyExistsMapper` |
| `409` | `CONFLICT` | party už má aktivní roli | `RelationshipAlreadyExistsMapper` |
| `401`/`403` | — | chybějící token / špatná role | Quarkus security (`@RolesAllowed`) |

## Vysílané události (Kafka topic `party.events`)

Klíčováno přes `aggregateId` (UUID party), obálka `{eventType, aggregateId, occurredAt, payload}`:

`PartyCreated`, `PartyVerified`, `KycLevelChanged`, `PartyStatusChanged`, `RelationshipAdded`, `RelationshipTerminated`, `AddressUpdatedFromRob`, `case.created`, `case.transitioned`, `case.evidence.linked`. Všechny nesou `version = 1`.
