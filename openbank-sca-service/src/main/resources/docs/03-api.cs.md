# API

REST kontrakt pod základní cestou `/api/v1/sca`. Major verze v URL (`v1`) odpovídá `openbank.api.version = "1"` (ADR-0048). Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs`.

> **Poznámka ke kontraktu:** ve službě je přítomen `openapi.yaml`, ale je **zastaralý** vůči implementaci (uvádí jiný dev port, jiné enumy typů výzvy/účelu a mírně odlišná těla requestů). Autoritativní kontrakt níže vychází ze skutečného `ScaResource` a doménového kódu; `openapi.yaml` by měl být přegenerován tak, aby odpovídal (sledováno jako follow-up). Při rozporu rozhoduje kód.

## Endpointy

| Metoda | Cesta | Auth (`@Authorize`) | Účel |
|---|---|---|---|
| `POST` | `/api/v1/sca/challenges` | — (autentizováno) | Inicializace SCA výzvy |
| `POST` | `/api/v1/sca/challenges/{id}/verify` | `scaChallenge.verify` | Ověření (OTP) nebo poll decoupled stavu |
| `GET` | `/api/v1/sca/challenges/{id}` | — (autentizováno) | Získání stavu výzvy |
| `POST` | `/api/v1/sca/parties/{partyId}/devices` | `device.enroll` | Zápis credentialu zařízení |
| `GET` | `/api/v1/sca/parties/{partyId}/devices` | `device.list` | Výpis zapsaných zařízení party |
| `POST` | `/api/v1/sca/challenges/{id}/decision` | `scaChallenge.decide` | Záznam out-of-band schválení/zamítnutí |

`@Authorize` se vyhodnocuje vůči OPA sidecaru (ADR-0034, ve výchozím stavu advisory — `authz.enforce=false`). Endpointy `parties/{partyId}/devices` navíc vynucují vlastnictví v kódu: principal, jehož jméno je UUID (party, ne operátor), smí jednat pouze nad **vlastním** `partyId`; `ROLE_OPERATOR` / `ROLE_ADMIN` smí jednat jménem party.

## Inicializace — `POST /api/v1/sca/challenges`

Request:
```json
{
  "partyId": "uuid",
  "purpose": "PAYMENT_INITIATION | CONSENT_GRANT | LOGIN | AGENT_ACTION | SENSITIVE_DATA_ACCESS",
  "preferredMethod": "PUSH_NOTIFICATION | TOTP | BIOMETRIC",
  "dynamicLinkingData": {
    "amount": "100.00", "currency": "EUR",
    "creditorIban": "…", "creditorName": "…", "reference": "…"
  },
  "redirectUrl": "https://…"
}
```
`preferredMethod`, `dynamicLinkingData`, `redirectUrl` jsou volitelné (metoda výchozí `PUSH_NOTIFICATION`). Odpověď `201` s tělem výzvy (níže). TTL je 300 s.

### Idempotence
Pošlete `Idempotency-Key` (preferováno) nebo `X-Request-ID`. Replay vrátí cachované tělo `201` s hlavičkou `X-Idempotency-Replayed: true`. REST cache klíč je `sca:initiate:{partyId}:{key}` (Redis, 300 s). Nezávisle use case odvodí idempotenční klíč z celého příkazu (party + účel + metoda + pole dynamického provázání + redirectUrl) a vrací stejnou výzvu pro identické re-inicializace.

## Ověření — `POST /api/v1/sca/challenges/{id}/verify`

Request: `{ "partyId": "uuid", "otp": "123456" }` (`otp` povinné pro TOTP).
- **OTP metody**: správné OTP ⇒ `COMPLETED`; chybné OTP zvýší `attemptCount` a po dosažení `maxAttempts` (3) se výzva stane `FAILED` a volání vrátí `401`.
- **Decoupled metody (PUSH/BIOMETRIC)**: vrací aktuální výzvu. `COMPLETED` jen pokud zapsané zařízení už poslalo APPROVED rozhodnutí; jinak zůstává `PENDING` (pokus se nespotřebuje) — nikdy ne automaticky (ADR-0021).

## Získání — `GET /api/v1/sca/challenges/{id}`

Odpověď `200` s tělem výzvy, `404` pokud neznámá.

## Zápis zařízení — `POST /api/v1/sca/parties/{partyId}/devices`

Request:
```json
{ "credentialId": "stabilní-credential-id",
  "publicKey": "<base64 X.509 SubjectPublicKeyInfo>",
  "algorithm": "ES256 | ED25519" }
```
Odpověď `201` s tělem zapsaného zařízení. Emituje outbox událost `DEVICE_ENROLLED`.

## Záznam rozhodnutí — `POST /api/v1/sca/challenges/{id}/decision`

Autentizováno jako zapsané zařízení/party (*jiný* principal než volající verify).
```json
{ "credentialId": "…", "decision": "APPROVED | DENIED",
  "signature": "<base64 podpis nad payloadem dynamického provázání>" }
```
Služba ověří podpis vůči veřejnému klíči zařízení nad `id | decision | amount | currency | creditorIban | reference` (RTS čl. 5 dynamické provázání) před jeho záznamem. Rozhodnutí je **write-once** (druhé volání je odmítnuto, aby DENIED nešlo přepsat na APPROVED).

## Těla odpovědí

**ScaChallengeResponse**: `id`, `partyId`, `purpose`, `method`, `status` (PENDING/COMPLETED/FAILED/EXPIRED/CANCELLED), `expiresAt`, `completedAt`, `attemptCount`, `maxAttempts`.

**EnrolledDeviceResponse**: `id`, `partyId`, `credentialId`, `algorithm`, `enrolledAt`.

## Chybový model

Chyby používají sdílený `ApiError` (libs): `{ traceId, status, code, message }`.

| Doménový stav | HTTP | `code` |
|---|---|---|
| Výzva / zařízení nenalezeno | `404` | `NOT_FOUND` |
| Výzva expirovala | `422` | `VALIDATION_ERROR` |
| Překročen počet pokusů | `429` | `VALIDATION_ERROR` |
| Ověření OTP selhalo (terminální) | `401` | `UNAUTHORIZED` |
| Výzva nečeká na rozhodnutí / rozhodnutí existuje | `409` | `VALIDATION_ERROR` |
| Zařízení nepatří party výzvy | `403` | `FORBIDDEN` |
| Neplatné assertion zařízení (špatný podpis) | `401` | `UNAUTHORIZED` |
| Volající jedná nad cizími zařízeními | `403` | `FORBIDDEN` |

## Verzování

Změny API se řídí klasifikací OpenAPI-diff (`oasdiff`), nezávisle na release verzi (ADR-0048). Release verze žije v `version.txt` (aktuálně `0.4.0`); verze API kontraktu žije v `openapi.yaml:info.version` a musí být v rámci follow-upu přegenerování kontraktu znovu sladěna s kódem.
