# API & kontrakty

REST kontrakt je popsán v [`openapi.yaml`](../openapi.yaml) (`info.version` 1.3.0; API major verze `1` ⇒ URL prefix `/api/v1`). Tam, kde se OpenAPI dokument a implementace rozcházejí, tato stránka dokumentuje **kód jako zdroj pravdy** a rozdíl označuje.

## Základní cesta

- **In-cluster base:** `http://openbank-party-service:8111/api/v1`
- **OpenAPI spec:** `/q/openapi` (management port 8085) — statický kontrakt je rovněž přibalen v `src/main/resources/openapi.yaml`
- **Swagger UI:** `/api/docs` (vždy zahrnuto dle `quarkus.swagger-ui`)

## Autentizace & autorizace

Všechny endpointy vyžadují **Keycloak Bearer token** (realm `openbank`). Role per endpoint (`@RolesAllowed`):

V produkčním prostředí `/api/*` na HTTP i mTLS listeneru používá autentizační mechanismus bearer. Důvěryhodný klientský certifikát je nutný pro navázání TLS na portu 8443, ale sám o sobě neurčuje identitu volajícího API: certifikát bez tokenu vrací 401. Na tomto listeneru klient potřebuje certifikát i platný bearer; stávající HTTP listener nadále vyžaduje bearer. Tím se brání tomu, aby služba s důvěryhodným transportním certifikátem získala identitu v API bez tokenu (#12511). `PartyListenerAuthParityIT` ověřuje oba listenery skutečnými HTTP požadavky. Volba mechanismu nemění poradní režim OPA ani kontroly rolí jednotlivých endpointů níže.

| Endpoint | Role |
|---|---|
| `GET /parties`, `GET /parties/search`, `GET /parties/{id}`, `GET /parties/{id}/documents` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_KYC`, `ROLE_API` |
| `POST /parties`, `POST /parties/{id}/documents` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_KYC` |
| `PATCH /parties/{id}` | `ROLE_OPERATOR`, `ROLE_ADMIN` (+ `@Authorize(action="party.update")` přes OPA, advisory) |
| `PUT /parties/{id}/kyc-status` | `ROLE_ADMIN`, `ROLE_KYC` |
| `DELETE /parties/{id}` | `ROLE_ADMIN` |

OPA autorizace (ADR-0034) běží v **advisory režimu** (`authz.enforce=false`): zamítnutí z `@Authorize` se logují na WARN, ale request pokračuje. Překlopení do enforce je pozdější fáze.

## Idempotence & deduplikace

- `POST /api/v1/parties` **vyžaduje** hlavičku `Idempotency-Key`. Klíč se předává do create příkazu.
- Nezávisle na tom je **e-mail unikátní** (DB omezení + `findByEmail`). Vytvoření party s e-mailem, který už existuje, vrací **409 Conflict** (`PartyAlreadyExistsException`). To je primární pojistka proti replay/duplicitám.

## Endpointy

### Vytvoření party

```http
POST /api/v1/parties
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: <client-uuid-v4>

{
  "partyType": "INDIVIDUAL",
  "legalName": "Jan Novák",
  "tradingName": null,
  "dateOfBirth": "1985-03-21",
  "nationality": "CZE",
  "taxId": null,
  "registrationNumber": null,
  "email": "jan.novak@example.com",
  "phone": "+420777123456",
  "address": { "line1": "Václavské nám. 1", "line2": null, "city": "Praha", "postalCode": "11000", "countryCode": "CZ" }
}
```

```http
201 Created
Location: /api/v1/parties/{uuid}

{
  "id": "…", "partyType": "INDIVIDUAL", "status": "PENDING_KYC",
  "legalName": "Jan Novák", "email": "jan.novak@example.com",
  "kycStatus": "NOT_STARTED", "createdAt": "…", "updatedAt": "…"
}
```

- `partyType` ∈ INDIVIDUAL / SOLE_TRADER / COMPANY / TRUST.
- `email` je **povinný**; jeho vynechání vrací `400` (`"email is required"`).
- Nové party začínají `status=PENDING_KYC`, `kycStatus=NOT_STARTED`, `amlStatus=NOT_SCREENED`.
- Vedlejší efekt: řádek `party_outbox` s `PARTY_CREATED`, odeslán do Kafky do ~5 s.

### Get / výpis / vyhledávání

```http
GET /api/v1/parties/{id}
GET /api/v1/parties?page=0&size=20&status=PENDING_KYC
GET /api/v1/parties/search?q=Novak&limit=20&cursor=<opaque>
```

- **Výpis** je stránkovaný page/size (size omezeno na `[1,100]`); volitelný filtr `status` (PENDING_KYC / ACTIVE / SUSPENDED / CLOSED) pohání onboarding cockpit funnel (ADR-0068). Odpověď vrací zpět `statusFilter`. Handler nastaví kosmetickou hlavičku `X-Party-List-Mode: enriched|standard` řízenou flagem `party-list-enriched`.
- **Vyhledávání** (ADR-0055) je stránkované keyset kurzorem a hradlené feature flagem `party-search` (`@FeatureFlag`). Prázdný/`*`/term kratší než 2 znaky vrací prázdnou stránku (žádný full-table enumerate). Funguje pro všechny země a pokrývá to, co operátor skutečně zná:
  - **Jméno / příjmení** — bez ohledu na diakritiku a velikost písmen, po slovech a částečně (`novak ja` najde `Jan Novák`; shodovat se musí každé slovo). Podloženo `party_fold()` + trigramovými indexy (Flyway `V27`).
  - **Národní / osobní identifikátor** libovolné země (rodné číslo, PESEL, SSN, NINO, DIČ…) — **přesná** shoda po odstranění oddělovačů, nikdy podřetězec, takže identifikátory nejde vyjmenovat podle prefixu. CZ/SK rodné číslo se navíc dohledá přes RČ blind index; surové RČ se v SQL nikdy neporovnává. Telefon se shoduje podle posledních 9 číslic.
  - **Jméno + datum narození** (`Novák 15.6.1985`, `Smith 1985-06-15`, `Doe 12/31/1985`; nejednoznačné d/m vs m/d hledá obě data) — datum zužuje shodu jména; samotné datum nevrací nic.
  - Vrací **minimalizovaný** souhrn včetně `dateOfBirth`, aby šlo rozlišit jmenovce.

### Aktualizace party

```http
PATCH /api/v1/parties/{id}
{ "email": "...", "phone": "...", "tradingName": "...", "address": { ... } }
```

Částečná aktualizace — mění se jen poskytnutá pole. Emituje `PARTY_UPDATED`.

### Dokumenty

```http
POST /api/v1/parties/{id}/documents
{ "documentType": "PASSPORT", "documentNumber": "...", "issuingCountry": "CZ", "expiryDate": "2030-01-01" }

GET  /api/v1/parties/{id}/documents
```

`documentType` (enum v kódu) ∈ NATIONAL_ID / PASSPORT / DRIVING_LICENCE / COMPANY_REGISTRATION / TAX_ID.

### KYC status

```http
PUT /api/v1/parties/{id}/kyc-status
{ "kycStatus": "APPROVED" }
```

`kycStatus` ∈ NOT_STARTED / IN_PROGRESS / APPROVED / REJECTED / EXPIRED. Přepočítá `status` party přes dvouklíčovou bránu a emituje `KYC_STATUS_CHANGED`.

### Výmaz party (GDPR čl. 17)

```http
DELETE /api/v1/parties/{id}
→ 204 No Content
```

Anonymizuje veškeré PII (legal name → `ANONYMIZED`, e-mail → náhodný tombstone `erased-<uuid>@erased.invalid`, phone/address/dob/nationality/taxId/registrationNumber → null), nastaví `status=CLOSED` a emituje `PARTY_ERASED`. Tombstone e-mail zachová unikátní omezení, aniž by byl zpětně korelovatelný na subjekt.

## Chybový model

Jednotný přes `openbank-libs.api.error.ApiError`, mapovaný v `ExceptionMappers`.

| HTTP | Příčina |
|---|---|
| 400 | validační chyba (např. chybějící `email`, neplatná hodnota enumu) |
| 401 | chybějící / neplatný token |
| 403 | chybějící role pro endpoint; nebo vypnutý feature flag (`@FeatureFlag` mapuje na chybu „feature disabled") |
| 404 | `PartyNotFoundException` — id neexistuje |
| 409 | `PartyAlreadyExistsException` — e-mail už registrován |
| 429 | rate limit per token (`openbank.rate-limit`, 150 souběžných) |
| 500 | neočekávaná chyba |

> Poznámka k OpenAPI: rozdíl mezi přibaleným `openapi.yaml` (DELETE výmaz není uveden; názvy enumů `documentType`/`kycStatus` se liší; server URL ukazuje port 8126) a běžící službou (port 8111, výše uvedené enumy a DELETE) je známý contract-drift k narovnání. Autoritativní je kód.

## Eventy

Odchozí topic: `openbank.party.events` (JSON, string serializer).

| Typ eventu | Spouštěč | Payload (klíčová pole) |
|---|---|---|
| `PARTY_CREATED` | vytvoření party | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `PARTY_UPDATED` | aktualizace party | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `KYC_STATUS_CHANGED` | zaznamenán výsledek KYC nebo AML (vč. překlopení na ACTIVE) | partyId, partyType, status, kycStatus, legalName, email, occurredAt |
| `PARTY_ERASED` | GDPR výmaz | partyId, erasedAt |

Konzumované příchozí topicy (group `openbank-party-service`, `auto.offset.reset=earliest`):

| Topic | Zpracované typy eventů | Efekt |
|---|---|---|
| `openbank.kyc.events` | `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED` | nastaví `kycStatus`, přepočítá status |
| `openbank.aml.events` | `newStatus/status` ∈ `CLEARED`, `BLOCKED` | nastaví `amlStatus`, přepočítá status |

## Verzování & zpětná kompatibilita

- **API verze v URL** (`/api/v1/...`); `openbank.api.version=1`. Breaking změny ⇒ `/api/v2`.
- **Osa OpenAPI kontraktu** je nezávislá na release `version.txt` (ADR-0048).
- **Eventy** jsou na `openbank.party.events` pouze aditivní; breaking změny by použily nový topic.
