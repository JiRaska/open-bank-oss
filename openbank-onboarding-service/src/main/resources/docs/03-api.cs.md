# API & kontrakty

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). Všechny endpointy jsou **read-only GETy** nad read-modelem onboardingu.

## Základní cesta

- **Produkční base:** `http://openbank-onboarding-service:8130/api/v1` (v clusteru)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.always-include=true`)

## Autentizace

Služba je napojena na **Keycloak OIDC** (realm `openbank`, klient `openbank-services`). Při zapnutém OIDC je vyžadován platný Bearer token (v profilech `%dev` a `%test` je OIDC vypnuté).

> **Stav autorizace (TBD vs. ADR-0068):** ADR-0068 §7 specifikuje, že každý onboarding endpoint má od prvního dne `@Authorize`/OPA v režimu **enforce**, s UI oprávněními `onboarding:view`, `onboarding:act.soft`, `onboarding:decide`, `onboarding:override`. Současný `OnboardingResource` nenese **žádné anotace `@RolesAllowed`/`@Authorize`** — autentizace je vynucena, ale autorizace dle rolí ještě není zadrátována. To je známá mezera k uzavření před produkcí.

## Idempotence

Neaplikuje se. Všechny tři endpointy jsou read-only GETy, takže není požadavek na `Idempotency-Key`. Na straně příjmu je projekce událostí upsert podle `party_id`, a tedy přirozeně idempotentní — přehrání události vyprodukuje stejný řádek.

## Endpointy

### Výpis onboardingových záznamů

```http
GET /api/v1/onboarding/records?page=0&size=20&stage=KYC_OPEN
Authorization: Bearer <token>
```

Query parametry:

| Param | Typ | Default | Poznámky |
|---|---|---|---|
| `page` | integer | `0` | index stránky od nuly |
| `size` | integer | `20` | serverově ořezáno na `1..100` (`size.coerceIn(1, 100)`) |
| `stage` | string | — | volitelný filtr fáze trychtýře; case-insensitive, nerozpoznané hodnoty se ignorují (bere se jako bez filtru) |

`stage` ∈ `REGISTERED`, `KYC_OPEN`, `KYC_DOCUMENTS_REQUIRED`, `KYC_UNDER_REVIEW`, `SCA_PENDING`, `ACTIVE`, `BLOCKED`.

```http
200 OK
Content-Type: application/json

{
  "items": [
    {
      "partyId": "b1f9…",
      "legalName": "Jane Doe",
      "email": "jane@example.com",
      "partyStatus": "PENDING_KYC",
      "kycCaseId": null,
      "kycStatus": null,
      "scaEnrolled": false,
      "deviceCount": 0,
      "funnelStage": "REGISTERED",
      "blockedReason": null,
      "createdAt": "2026-06-09T10:42:13Z",
      "updatedAt": "2026-06-09T10:42:13Z"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20,
  "stageFilter": "KYC_OPEN"
}
```

`stageFilter` je přítomen jen tehdy, byl-li aplikován filtr `stage`.

### Detail záznamu pro party

```http
GET /api/v1/onboarding/records/{partyId}
```

`partyId` je UUID. Vrací `OnboardingRecordDto`, nebo `404`, pokud pro danou party žádný záznam neexistuje.

```http
404 Not Found
{ "code": "NOT_FOUND", "message": "Onboarding record not found for party <id>" }
```

### KPI počty trychtýře

```http
GET /api/v1/onboarding/funnel
```

Vrací mapu každé `FunnelStage` na počet záznamů (fáze s nulou jsou zahrnuty s počtem `0`).

```http
200 OK
{
  "REGISTERED": 12,
  "KYC_OPEN": 45,
  "KYC_DOCUMENTS_REQUIRED": 8,
  "KYC_UNDER_REVIEW": 23,
  "SCA_PENDING": 6,
  "ACTIVE": 301,
  "BLOCKED": 4
}
```

## Chybový model

OpenAPI schéma `ApiError` je minimální: `{ code, message }`. Jedinou chybovou odpovědí, kterou resource aktuálně vydává, je výše uvedené `404` (`code=NOT_FOUND`). Chyby autentizace (`401`) produkuje Quarkus OIDC vrstva, je-li zapnutá. Bohatá obálka problem+json na této službě zatím není (TBD — sjednotilo by se s platformovým `ApiError` používaným money-path službami).

## Příchozí události (kontrakt příjmu)

Služba je primárně **konzument událostí**. Čte JSON string payloady ze tří topiků a mapuje rozpoznané hodnoty `eventType` na sealed `OnboardingEvent`:

| Topik (kanál) | Rozpoznané `eventType` | Projektováno na |
|---|---|---|
| `openbank.party.events` (`party-events-in`) | `PARTY_CREATED` | nový záznam, fáze `REGISTERED` |
| | `PARTY_STATUS_CHANGED`, `KYC_STATUS_UPDATED` | status party + přepočtená fáze |
| `openbank.kyc.events` (`kyc-events-in`) | `KYC_CASE_OPENED` | nastaví `kycCaseId`, kyc `OPEN` |
| | `KYC_CASE_STATUS_CHANGED`, `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED` | kyc status + přepočtená fáze + `blockedReason` |
| `openbank.sca.events` (`sca-events-in`) | `DEVICE_ENROLLED` | `scaEnrolled=true`, `deviceCount++` |

Parsování je **tolerantní**: konzument přijímá `kycCaseId` nebo `caseId`, `newStatus` nebo `status`, `occurredAt` defaultuje na nyní, je-li chybějící, a tiše zahazuje neznámé typy událostí či neparsovatelné payloady (zalogováno, pak acknowledgnuto). To odpojuje read-model od drobného driftu schématu producentů.

## Verzování

- **Verze API v URL** (`/api/v1/...`). `openbank.api.version=1`, servíruje `openbank-libs`. Breaking změny ⇒ `/api/v2`.
- **Verze API kontraktu** je `openapi.yaml:info.version` (aktuálně `1.0.0`), samostatná osa API kontraktu (ADR-0048) — nezávislá na release `version.txt`.
- **Příjem událostí** je tolerantní z principu (aditivní změny producentů jsou pohlceny); breaking změnu producenta řeší vlastník topiku, nikoli tato služba.
