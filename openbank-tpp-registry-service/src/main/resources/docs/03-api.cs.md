# API

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). Všechny endpointy žijí pod `/api/v1/tpp-registry` — URL major verze (`v1`) odpovídá `openbank.api.version = "1"` (ADR-0048). Swagger UI je na `/api/docs`.

> **Poznámka kontrakt/kód:** `openapi.yaml` používá na `/check` query parametr `permission` s enumem `[AIS, PIS, PIIS]`, zatímco implementovaný `TppRegistryResource` čte query parametr `role` mapovaný na enum `TppRole` `[AISP, PISP, PIISP, ASPSP]`. Berte kód jako běžící chování; pojmenování v OpenAPI se sjednocuje. Stejně tak blok `servers` v OpenAPI stále ukazuje placeholder port (`8123`); služba ve skutečnosti naslouchá na **8108**.

## Endpointy

| Metoda | Cesta | Účel | Auth | Idempotentní |
|---|---|---|---|---|
| `GET` | `/api/v1/tpp-registry/check` | Kontrola autorizace pro TPP+roli | OIDC | nepouž. (read) |
| `POST` | `/api/v1/tpp-registry` | Registruj nový TPP | OIDC | `Idempotency-Key` |
| `GET` | `/api/v1/tpp-registry` | Vypiš / filtruj TPP | OIDC | nepouž. (read) |
| `GET` | `/api/v1/tpp-registry/{tppId}` | Získej jeden TPP | OIDC | nepouž. (read) |
| `POST` | `/api/v1/tpp-registry/{tppId}/blacklist` | Zařaď TPP na blacklist | OIDC + `@Authorize` | `Idempotency-Key` |
| `POST` | `/api/v1/tpp-registry/sync/eba` | Spusť sync registru EBA | OIDC | `Idempotency-Key` (TTL 300 s) |
| `GET` | `/api/v1/tpp-registry/sync/state` | Přečti poslední stav syncu | OIDC | nepouž. (read) |

### `GET /check`

Query parametry: `tppId` (povinný), `role` (povinný, jeden z `AISP|PISP|PIISP|ASPSP`).
Vrací `200` s `TppAuthorizationResult`, je-li autorizován, **`403`** se stejným tělem (nesoucím `reason`), pokud ne. Důvody odmítnutí: nenalezen, status není ACTIVE, roli nemá, expirovaný QWAC certifikát.

### `POST /api/v1/tpp-registry` — registrace

Tělo: `RegisterTppRequest` (`tppId`, `name`, `permissions`/role, `eidasCertFingerprint`, `countryCode`, `nca`). Implementace mapuje na `RegisterTppCommand` (`tppId`, `name`, `countryCode`, `nca`, `roles`, `qwacSubjectDn?`, `qsealSubjectDn?`). Nové záznamy vznikají jako `ACTIVE`. Duplicitní `tppId` → `409 CONFLICT`. Vrací `201` s vytvořeným `TppEntry`.

### `GET /api/v1/tpp-registry` — výpis

Query parametry (vše volitelné): `countryCode`, `role`, `status`, `limit` (default 50), `afterCursor`. Vrací `200` s `{ "tpps": [...], "count": n }`.

### `POST /{tppId}/blacklist`

Tělo: `{ "reason": "<text>" }` (defaultně „No reason provided", pokud chybí). Nastaví status `BLACKLISTED`, zaznamená `blacklistedAt` a `blacklistReason`. Chráněno `@Authorize(action = "tppRegistry.blacklist", resource = "#tppId")` (OPA). Vrací `200` s aktualizovaným `TppEntry`.

### `POST /sync/eba` a `GET /sync/state`

`POST /sync/eba` spustí `attemptEbaSync` (fault-tolerant) a uloží výsledný `EbaRegisterSyncState`. Aktuálně stub vracející `errorMessage = "EBA sync not yet implemented — manual registration only"`. `GET /sync/state` vrací poslední uložený stav (nebo vynulovaný default).

## Idempotence

Mutující endpointy přijímají hlavičku `Idempotency-Key`. První požadavek se provede a odpověď se uloží do Redisu (`IdempotencyStore` z openbank-libs) pod klíčem v rozsahu operace:

- register: `tpp:register:{tppId}:{key}`
- blacklist: `tpp:blacklist:{tppId}:{key}`
- sync: `tpp:sync:{key}` (TTL 300 s)

Replay vrací uložený status + tělo s hlavičkou `X-Idempotency-Replayed: true`. Prázdný/chybějící klíč caching přeskočí (operace se přesto provede).

## Chybový model

`ExceptionMappers` produkují JSON těla tvaru `{"error": "<CODE>", "message": "..."}`:

| Výjimka | HTTP | `error` |
|---|---|---|
| `TppNotFoundException` | 404 | `NOT_FOUND` |
| `TppAlreadyExistsException` | 409 | `CONFLICT` |
| `EbaSyncUnavailableException` | 503 | `SERVICE_UNAVAILABLE` |
| `IllegalArgumentException` | 400 | kanonický libs `ApiError` (traceId/code/status) — ADR-0049 D4 |

Schéma `ApiError` v OpenAPI (`code`, `message`) dokumentuje kanonický chybový kontrakt z libs používaný pro `404`/validační odpovědi.

## Verzování

- **Osa API kontraktu:** `openapi.yaml:info.version` (`1.0.0`); URL major `v1` == `openbank.api.version`. Změna API si klasifikuje vlastní bump z OpenAPI diffu (`oasdiff`), nezávisle na release verzi (ADR-0048).
- Hlavičky odpovědi `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje openbank-libs.
