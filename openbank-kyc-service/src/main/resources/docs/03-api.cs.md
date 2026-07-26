# API

REST kontrakt je popsán v [`openapi.yaml`](../openapi.yaml) (`info.version: 1.1.0`). Všechny business endpointy jsou obsluhovány pod URL prefixem `/api/v1` (`openbank.api.version = 1`, ADR-0048). Spec je rovněž obsluhována živě na `/q/openapi` a vykreslena na `/api/docs` (Swagger UI).

> **Poznámka k driftu spec/kódu:** commitnutý `openapi.yaml` je na pár místech mírně před / za implementací (např. hodnoty enumu `checkType` a názvy polí request body). Kde se liší, tabulky níže odrážejí **skutečnou implementaci `KycResource`**, která je pro volající dnes autoritativní. Sladění OpenAPI dokumentu s resourcem je sledováno jako follow-up kontraktního testu.

## Endpointy (jak jsou implementovány v `KycResource`)

| Metoda | Cesta | Role | Účel |
|---|---|---|---|
| `GET` | `/api/v1/kyc/cases?page=&size=&status=` | `ROLE_VIEWER`,`ROLE_OPERATOR`,`ROLE_ADMIN`,`ROLE_KYC`,`ROLE_COMPLIANCE`,`ROLE_API` | Stránkovaný výpis; volitelný `status` filtr funnelu (ADR-0068) |
| `POST` | `/api/v1/kyc/cases` | `ROLE_OPERATOR`,`ROLE_ADMIN`,`ROLE_KYC` | Otevření nového případu pro party |
| `GET` | `/api/v1/kyc/cases/{id}` | viewer/operator/admin/kyc/compliance/service | Načtení případu podle id |
| `GET` | `/api/v1/kyc/cases/party/{partyId}` | viewer/operator/admin/kyc/compliance/service | Načtení posledního případu pro party (404 pokud žádný) |
| `PUT` | `/api/v1/kyc/cases/{id}/checks/{checkType}` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.updateCheck)` | Zaznamenání výsledku kontroly |
| `POST` | `/api/v1/kyc/cases/{id}/approve` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.approve)` | Schválení (čtyři oči, ADR-0068) |
| `POST` | `/api/v1/kyc/cases/{id}/reject` | `ROLE_ADMIN`,`ROLE_KYC` + `@Authorize(kycCase.reject)` | Zamítnutí (čtyři oči, ADR-0068) |

### Request body (implementace)

- **Otevření případu** — `{ "partyId": "<uuid>" }`. Případ je vytvořen se stavem `OPEN`, rizikem `MEDIUM`, 30denní `expiresAt` a čtyřmi PENDING kontrolami (IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING).
- **Aktualizace kontroly** — `{ "status": "PENDING|PASSED|FAILED|MANUAL_REVIEW", "result": "<poznámka?>" }`; `{checkType}` je jedno z `IDENTITY`, `ADDRESS`, `PEP_SCREENING`, `SANCTIONS_SCREENING`, `ADVERSE_MEDIA`. Když projdou všechny kontroly, případ přejde do `UNDER_REVIEW`; pokud kterákoli selže, přejde do `REJECTED`.
- **Schválení** — `{ "reviewedBy": "<revizor>" }`.
- **Zamítnutí** — `{ "reviewedBy": "<revizor>", "reason": "<text>" }`.

> `openapi.yaml` dokumentuje pole update-check jako `result` (enum) + `notes` a review body klíčované na `reviewerId`; běžící kód používá `status`/`result` a `reviewedBy`. Důvěřujte tvarům z kódu výše.

## Model odpovědi

`KycCaseResponse` vrací agregát `KycCase`: `id`, `partyId`, `caseType`/`status`, `riskLevel`, `checks` (mapa kontrola → stav), `createdAt`, `updatedAt`. List endpoint vrací obálku `{ items, total, page, size, statusFilter }`.

## Verzování

- **Verze API kontraktu** je v `openapi.yaml:info.version` (aktuálně `1.1.0`); její major se rovná URL `/api/v{N}` a `openbank.api.version` (ADR-0048).
- **Release verze** je nezávislá a sledovaná v `version.txt` (aktuálně `0.2.0`), vlastněná release-please.
- Sdílený filtr `openbank-libs` obsluhuje hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info`.

## Idempotence

Tato služba **nepoužívá** cache `Idempotency-Key` na úrovni požadavku. Idempotence je vynucena na úrovni domény:

- `openCaseForParty` (cesta konzumenta `PARTY_CREATED`) nejprve znovu načte podle `partyId` a parciální unikátní index `uq_kyc_cases_active_party` (V5) odmítne závodící druhý insert — takže replay party streamu nikdy nevytvoří duplicitní otevřené případy.
- Ruční endpoint `POST /cases` není deduplikován klíčem; je to akce operátora za kontrolou role.

## Error model

Chyby používají sdílený `com.openbank.libs.api.error.ApiError` (`{ id, status, code, message }`). Chybějící případ vyvolá `KycCaseNotFoundException`, mapovaný `KycNotFoundMapper` na **HTTP 404** s `code = NOT_FOUND`. `GET /cases/party/{partyId}` vrací holé 404, když žádný případ neexistuje.

## Auth

- **AuthN:** Keycloak OIDC, RS256 JWT bearer (`bearerAuth`). OIDC je vypnuto v `%dev` a `%test`.
- **AuthZ:** Quarkus `@RolesAllowed` per endpoint, plus `@Authorize` na mutacích čtyř očí / kontrol vyhodnocované proti OPA sidecaru (defaultně advisory — `authz.enforce=false`, ADR-0034).
