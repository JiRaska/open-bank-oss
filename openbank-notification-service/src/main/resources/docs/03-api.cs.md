# API

REST kontrakt je formalizován v [`openapi.yaml`](../../openapi.yaml) (`Notification Service API`, OpenAPI 3.1.0, `info.version 1.2.0`). Všechny endpointy jsou pod `/api/v1` — major verze v URL se rovná `openbank.api.version` (`1`), dle [ADR 0048](../../../../docs/adr/0048-two-version-axes.md). Interaktivní Swagger UI je na `/api/docs`, surová specifikace na `/q/openapi`.

> Většina business provozu **není** REST — je to Kafka consumer (`openbank.notification.requests`). REST plocha slouží registraci zařízení, čtecímu přístupu a break-glass workflow řízení výpravy.

## Autentizace a autorizace

Keycloak OIDC (`realms/openbank`, klient `openbank-services`), RS256 bearer tokeny. Role:

| Plocha | Role |
|---|---|
| Čtení notifikací (`GET /notifications…`) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |
| Výpis zařízení (`GET /devices`) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |
| Registrace zařízení (`POST /devices`) | `ROLE_OPERATOR`, `ROLE_API`, `ROLE_ADMIN` |
| Řízení výpravy (`/ops/dispatch…`) | `ROLE_OPERATOR`, `ROLE_ADMIN` (čtení i `ROLE_AUDITOR`) |

U řízení výpravy se **identita aktéra bere z autentizovaného JWT subjektu**, nikdy z těla požadavku — aby four-eyes pravidlo nešlo podvrhnout. `ROLE_SRE` je zamýšlená operátorská role, jakmile bude v realmu existovat; do té doby je gated na `ROLE_OPERATOR`/`ROLE_ADMIN`.

## Notifikace

### `GET /api/v1/notifications`

Výpis notifikací, stránkovaný. Query parametry: `partyId` (uuid, volitelný), `page` (default 0), `size` (default 20, omezeno 1..100). Specifikace OpenAPI dokumentuje i `status` a `offset`/`limit`; implementace používá `page`/`size` a filtruje dle `partyId`.

Vrací `{ items: [...], total, page, size }`. Každá položka: `id, partyId, channel, template, recipient, subject, status, sentAt, createdAt`.

### `GET /api/v1/notifications/{id}`

Získá jednu notifikaci podle `notificationId` (uuid). Vrací plný záznam včetně `body`. `404`, pokud nenalezeno.

## Zařízení (registr push tokenů)

### `POST /api/v1/devices`

Registrace (upsert) push device tokenu pro party. Tělo: `RegisterDeviceRequest` — `partyId` (uuid, povinné), `platform` (`FCM`|`APNS`, povinné), `token` (vydaný poskytovatelem, povinné), `appInstance` (povinné), `appVersion?`, `osVersion?`. Opětovná registrace stejného `(platform, token)` provede upsert.

- `201` → `DeviceResponse` (token se **nikdy nevrací zpět**).
- `400` → `ApiError` při chybějících/neplatných polích.

Zákaznická app se sem dostává přes `openbank-customer-edge`, který injektuje autoritativní `partyId` ze zákaznického JWT — `partyId` z těla se samo o sobě nikdy nedůvěřuje (prevence IDOR).

### `GET /api/v1/devices?partyId={uuid}`

Výpis registrovaných zařízení party. `partyId` je povinný (jinak `400`). Výpisy odhalují jen necitlivá metadata (id, platform, status, appInstance, verze, data) — **nikdy token**.

## Ops — Řízení výpravy (break-glass, ADR-0047)

### `GET /api/v1/ops/dispatch`

Vrací aktuální snapshot žádaného stavu plus nedávnou historii: `{ current: DispatchControlSnapshot, history: [...] }`. Snapshot je `{ controlKey, state (ENABLED|HALTED), version, reason, actor, effectiveFrom, deferredReviewRequired }`.

### `POST /api/v1/ops/dispatch/halt`

Break-glass — okamžité zastavení výpravy (jeden aktér). Tělo `{ reason? }`. Vrací nový HALTED snapshot s `deferredReviewRequired=true`.

### `POST /api/v1/ops/dispatch/resume/propose`

Návrh na obnovení (four-eyes). Tělo `{ reason? }`. `202` → `{ proposalId, state: PROPOSED }`.

### `POST /api/v1/ops/dispatch/resume/{proposalId}/approve`

Schválení a provedení obnovení. Schvalovatel se **musí lišit** od navrhovatele. `200` → nový ENABLED snapshot. `422` (`ApiError`) při porušení four-eyes (schvalovatel == navrhovatel). `404`, pokud návrh není znám.

### `POST /api/v1/ops/dispatch/resume/{proposalId}/reject`

Zamítnutí čekajícího návrhu. `200` → `{ proposalId, state: REJECTED }`. `404`, pokud není znám.

## Model chyb

JSON `ApiError` `{ code, message }`. Sdílené exception mappery v `openbank-libs` překládají:

| Podmínka | HTTP |
|---|---|
| Chybějící / neplatné pole | `400` `BAD_REQUEST` |
| Zdroj / návrh nenalezen | `404` |
| Porušení four-eyes (`MakerCheckerViolation`) | `422` |

## Idempotence

Tato služba **nemá vrstvu `Idempotency-Key`**. Vstupní Kafka cesta je at-least-once a redelivery uloží nový řádek notifikace (přijatelné — žádná peněžní cesta). Registrace zařízení je přirozeně idempotentní přes unikátní upsert `(platform, token)`.

## Verzování (dvě osy — ADR-0048)

- **API kontrakt:** `openapi.yaml:info.version = 1.2.0`; major v URL `/api/v1` == `openbank.api.version = 1`. API změna klasifikuje svůj vlastní bump z OpenAPI diffu.
- **Release:** `version.txt = 0.4.0`, vlastněno release-please. Obě osy jsou nezávislé a nesmí se nutit do rovnosti.

> **Pozn. ke specifikaci:** `openapi.yaml` `servers[0].url` uvádí port `8125`; služba ve skutečnosti naslouchá na `8112` (viz `application.yaml`). `8112` berte jako autoritativní; URL serveru ve specifikaci je zastaralý příklad.
