# API

Všechny endpointy jsou pod `/api/v1/consents`. Major verze kontraktu (`1`) mapuje na URL prefix `/api/v1` (`openbank.api.version=1`, ADR 0048). Content type je `application/json`.

> **Stav kontraktu:** se službou se dodává `openapi.yaml`, který je ovšem aktuálně **částečně zastaralý** oproti implementovanému resourcu (předchází SCA-gated životnímu cyklu — např. ukazuje `expiresAt`/`accountIds` a status `PENDING`, zatímco kód používá `validTo`/`accountIbans` a `PENDING_SCA` a vystavuje `/activate` a `/reject`). Popisy níže jsou **odvozeny z reálného `ConsentResource.kt`** a jsou autoritativní; sladění `openapi.yaml` s tímto stavem je evidovaný follow-up.

## Endpointy

| Metoda | Cesta | Účel | Úspěch |
|---|---|---|---|
| `POST` | `/api/v1/consents` | Vytvořit souhlas (status `PENDING_SCA`) | `201 Created` + `Location` |
| `GET` | `/api/v1/consents/{id}` | Získat souhlas dle id | `200` |
| `GET` | `/api/v1/consents/party/{partyId}` | Seznam souhlasů udělených party | `200` (pole) |
| `GET` | `/api/v1/consents/grantee/{granteeId}` | Seznam souhlasů držených příjemcem | `200` (pole) |
| `POST` | `/api/v1/consents/{id}/activate?scaSessionId={uuid}` | Aktivace po SCA → emituje `ConsentGranted` | `200` |
| `POST` | `/api/v1/consents/{id}/reject?reason={text}` | Odmítnutí → emituje `ConsentRejected` | `200` |
| `DELETE` | `/api/v1/consents/{id}?partyId={uuid}` | Odvolání → emituje `ConsentRevoked` | `200` |
| `POST` | `/api/v1/consents/{id}/validate` | Validace přístupu (scope/účet/příjemce) | `200` |

## Vytvoření souhlasu

`POST /api/v1/consents`

```json
{
  "partyId": "0e1f…",
  "granteeId": "PSDCZ-CNB-12345",
  "granteeType": "TPP",
  "granteeName": "Acme Aggregator a.s.",
  "scopes": ["ACCOUNTS_READ", "TRANSACTIONS_READ"],
  "accountIbans": ["CZ6508000000192000145399"],
  "validTo": "2026-09-01T00:00:00Z",
  "redirectUri": "https://tpp.example/callback",
  "tppTransactionId": "tpp-ref-abc-123"
}
```

- `accountIbans` může být `null` ⇒ souhlas pokrývá **všechny** účty party.
- `validTo` je **stropováno na straně serveru**: AIS scopy ⇒ max `now + 90 dní`; jinak `now + 365 dní`.
- Vrací vytvořený `Consent` se statusem `PENDING_SCA`. Souhlas je nepoužitelný, dokud není aktivován.

### Idempotence

Vytvoření souhlasu je idempotentní. Klíč je odvozen jako `consent:create:{granteeId}:{partyId}:{requestId}`, kde `requestId` je `tppTransactionId`, je-li přítomen, jinak hlavička `X-Request-ID`. Při opakování se vrátí cachovaná `201` odpověď s hlavičkou `X-Idempotency-Replayed: true`. Klíče jsou uloženy v Redis s TTL 24 h (`openbank.consent.idempotency-ttl-seconds=86400`). Bez idempotenčního klíče ⇒ bez ochrany proti opakování (každé volání vytvoří nový souhlas).

## Validace souhlasu

`POST /api/v1/consents/{id}/validate`

```json
{ "granteeId": "PSDCZ-CNB-12345", "requiredScope": "TRANSACTIONS_READ", "accountIban": "CZ65…" }
```

Odpověď:

```json
{ "valid": true, "reason": null, "code": null }
```

Validace selže (`valid:false`) s jedním ze strojově čitelných `code`:

| `code` | Význam |
|---|---|
| `CONSENT_NOT_FOUND` | žádný souhlas s tímto id |
| `CONSENT_GRANTEE_MISMATCH` | souhlas patří jinému příjemci |
| `CONSENT_NOT_ACTIVE` | není `ACTIVE`, nebo po `validTo` |
| `CONSENT_SCOPE_MISSING` | požadovaný scope není udělen |
| `CONSENT_ACCOUNT_NOT_COVERED` | IBAN je mimo seznam účtů souhlasu |

Validace vždy vrací HTTP `200`; rozhodnutí nese boolean `valid`.

## Model chyb

Mutace a vyhledávání vracejí sdílenou obálku `ApiError` z `openbank-libs` (`{ traceId, status, code, message }`). Mapování z `ExceptionMappers.kt`:

| HTTP | `code` | Kdy |
|---|---|---|
| `404` | `NOT_FOUND` | neznámé id souhlasu |
| `403` | `FORBIDDEN` | revoke s `partyId`, který souhlas nevlastní |
| `409` | `CONFLICT` | aktivace již `ACTIVE` souhlasu |
| `422` | `VALIDATION_ERROR` | SCA výzva nenalezena / neodpovídá (`partyId`/účel) / není `COMPLETED` |
| `503` | `SERVICE_UNAVAILABLE` | SCA služba nedostupná (po retries/circuit breakeru) |

## Autentizace & autorizace

- **AuthN:** Keycloak OIDC, RS256 bearer JWT (klient `openbank-services`). Vypnuto pouze v profilech `dev`/`test`.
- **AuthZ:** OPA sidecar přes libs interceptor `@Authorize` (ADR 0034). Aktuálně `authz.enforce=false` (advisory) defaultně; `DELETE` (revoke) nese `@Authorize(action = "consent.revoke", resource = "#id")`. Vlastnictví pro revoke je navíc vynuceno v doméně (query parametr `partyId` musí odpovídat vlastníkovi souhlasu ⇒ jinak `403`).

## Verzování

Jediný major kontraktu `v1`. Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`. Žádné zastaralé cesty (`api_deprecation.deprecated_paths: []`).
