# API

REST povrch je definován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). Je záměrně **minimální a pouze pro čtení** — auditní stopa nemá žádné zápisové API; ingest je výhradně přes Kafku (viz [02 — Architecture](./02-architecture.md)).

Major API verze je `1` (`openbank.api.version`), takže všechny cesty žijí pod `/api/v1` (ADR-0048 — osa API-kontraktu je nezávislá na release `version.txt`).

## Endpointy

### `GET /api/v1/audit/entries/{aggregateId}`

Získá auditní stopu pro jeden agregát (účet, klient, transakce, consent, KYC case, …), nejnovější první.

**Path parametry**

| Název | Typ | Poznámky |
|---|---|---|
| `aggregateId` | string | Id, jehož historii chceš (např. id účtu, id klienta, id transakce) |

**Query parametry**

| Název | Typ | Default | Poznámky |
|---|---|---|---|
| `limit` | integer | 100 | Velikost stránky; server-side omezeno na `1..500` |
| `eventType` | string | — | Deklarováno v `openapi.yaml`; **filtrování dle typu události zatím není v `AuditResource` implementováno** (resource aktuálně přijímá pouze `limit`). Považuj za rezervované. |
| `offset` | integer | 0 | Deklarováno v `openapi.yaml`; v resource zatím nezapojeno. Rezervované. |

> Poznámka k nesouladu (vychází z kódu): `openapi.yaml` dokumentuje `eventType`/`offset`, ale `AuditResource.getAuditTrail` aktuálně váže jen `aggregateId` a `limit`. Kontrakt je tu napřed před implementací; dokumentace to označuje, místo aby to skrývala.

**Odpověď `200`** — JSON pole `AuditEntryResponse`:

| Pole | Typ | Poznámky |
|---|---|---|
| `id` / `entryId` | uuid | Unikátní id záznamu (sloupec `entry_id`) |
| `aggregateId` | string | Agregát, kterého se událost týká |
| `aggregateType` | string | ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN |
| `eventType` | string | Název události producenta |
| `actorId` | string \| null | Kdo to spustil (`requestedBy` / `actorId` z payloadu) |
| `payload` | object/string | Původní payload události, doslovně |
| `occurredAt` | date-time | Business čas události |
| `correlationId` | string \| null | Trace correlation id |

(Kotlin doménová `AuditEntry` navíc nese `actorType`, `sourceService` a `recordedAt`; OpenAPI response schema je publikovaná podmnožina.)

## Autentizace & autorizace

- **AuthN:** Keycloak OIDC, RS256 bearer JWT (security scheme `bearerAuth`). OIDC je vypnuté pouze v profilech `%dev` a `%test`.
- **AuthZ:** `@RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")`. Endpoint **nikdy** není `@PermitAll` — neautentizovaný auditní log je sám o sobě auditní nález (regresní pojistka `AuditResourceSecurityTest`, kontrola K7).

| Volající | Požadovaná role |
|---|---|
| Dedikovaný read-only auditor | `ROLE_AUDITOR` |
| Platformový administrátor | `ROLE_ADMIN` |
| Compliance vyšetřovatel | `ROLE_COMPLIANCE` |

## Error model

| Status | Kdy |
|---|---|
| `200` | Stopa vrácena (klidně prázdné pole) |
| `401` | Chybějící/neplatný bearer token |
| `403` | Token nemá žádnou ze tří audit-reading rolí |
| `404` | Neznámá cesta |

Pro neznámé `aggregateId` neexistuje žádný `4xx` — agregát bez zaznamenaných událostí prostě vrátí `200` s `[]`.

## Idempotence & verzování

- **Idempotence:** pro read API nerelevantní. Zápisová cesta je event-driven a přirozeně idempotentní na úrovni řádku přes unikátní `entry_id` UUID.
- **Verzování:** response hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` obsluhuje `openbank-libs` (`ServiceInfoResource`, `ApiVersionResponseFilter`). Žádné cesty nejsou aktuálně deprecated.

## Interaktivní docs

Swagger UI je vystaveno na `/api/docs` (`quarkus.swagger-ui`, always-include zapnuto).
