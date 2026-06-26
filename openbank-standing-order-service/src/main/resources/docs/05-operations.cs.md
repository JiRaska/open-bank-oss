# Provoz

## Build & spuštění

```bash
# Build (lokálně, Gradle na hostu — nikdy v Dockeru)
./gradlew :openbank-standing-order-service:quarkusBuild

# Dev režim (live reload; OIDC vypnut v %dev)
./gradlew :openbank-standing-order-service:quarkusDev

# Kontejnerový image (fast-jar, nikdy uber-jar)
openbank-infra/scripts/build-push-service.sh standing-order-service
```

Dockerfile používá `-Dquarkus.package.jar.type=fast-jar` a runtime fáze kopíruje `quarkus-app/` (pravidlo repa — uber-jar ho nechá prázdný a pod crashloopuje).

## Endpointy & porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/standing-orders/...` | 8121 | business REST API |
| `/api/v1/info` | 8121 | ServiceInfoResource (build metadata, `openbank-libs`) |
| `/api/docs` | 8121 | Swagger UI (`swagger-ui.path`) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8121 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management rozhraní) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

**Management rozhraní je zapnuté** (`quarkus.management.enabled=true`) na portu **8085**, root-path `/q`, host `0.0.0.0`. Dev UI je vypnuté.

## Konfigurace

| Konfig / env var | Default | Účel |
|---|---|---|
| `quarkus.http.port` | `8121` | port aplikace |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ přepsat Vault** |
| reactive datasource URL | `postgresql://localhost:5432/openbank_standing_orders` | DB |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| OIDC auth-server-url | `http://localhost:8080/realms/openbank` | issuer |
| kafka bootstrap-servers | `localhost:29092` | brokeři |
| redis hosts | `redis://localhost:6379` | Valkey |
| `OPA_URL` | `http://localhost:8181` | base URL OPA sidecaru |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | dotazovací cesta OPA |
| `OPA_TIMEOUT_MS` | `500` | timeout rozhodnutí OPA |
| `AUTHZ_ENFORCE` | `false` | advisory vs enforce (ADR-0034) |
| `openbank.outbox.poll-interval` | `5s` | kadence dispatcheru |

Bezpečnostní hlavičky (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) jsou nastaveny v `application.yaml`. CORS povoluje `http://localhost:3000` s `Idempotency-Key` mezi povolenými hlavičkami.

## Health checky

- **Liveness:** `/q/health/live` (management port 8085) — JVM + ArC.
- **Readiness:** `/q/health/ready` (management port 8085) — zapojení DB / Kafka / Redis (SmallRye Health).

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8085 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8085 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Serverless / scale-to-zero tier (ADR-0057)

Standing-order **není** money-path služba a její provoz je nárazový (vytváření řízené adminem/zákazníkem, žádná real-time horká cesta). Je kandidátem na **scale-to-zero / low-replica tier** dle ADR-0057. Upozornění: outbox dispatcher je in-process `@Scheduled` smyčka — pokud je pod scalován na nulu, vyprazdňování outboxu se pozastaví, dokud pod neprobudí požadavek (nebo keep-warm probe). Pokud záleží na latenci outboxu, držte aspoň jednu teplou repliku, nebo přesuňte dispatching do always-on tieru. (Přesné přiřazení tieru: TBD — ověřit proti rollout matici ADR-0057.)

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="standing-order-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (create) | < 300 ms | zápis DB + insert outboxu |
| Zpoždění outboxu | < 30 s | stáří nejstaršího PENDING/processable řádku |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Outbox se nevyprazdňuje

1. Spočítej zpracovatelné řádky: `SELECT count(*) FROM standing_order_outbox WHERE status='PENDING'`.
2. Ověř, že dispatcher běží: `kubectl logs -l app=standing-order-service | grep OutboxDispatcher`. Běží každých 5 s s `SKIP` souběhem.
3. Zkontroluj circuit breaker: trvalé chyby vypnou `@CircuitBreaker` (volume 10, ratio 0.5, 5 s open). Prozkoumej `last_error` na chybných řádcích.
4. Ověř dostupnost Kafky a topic `openbank.standing-orders.order.event`.
5. Pokud byl pod scalován na nulu (ADR-0057 tier), smyčka pokračuje až po nastartování podu — viz serverless upozornění výše.

### Nepovolený přechod stavu (pause/resume/cancel odmítnut)

Symptom: 422/chyba z doménového `require`. Příčina: např. pause na ne-ACTIVE, resume na ne-PAUSED, nebo cancel na CANCELLED/COMPLETED. Akce: znovu načti příkaz (`GET /{id}`), sjednoť klientův pohled na `status`.

### Duplicitní vytvoření

Opakovaný `idempotencyKey` vrátí **existující** příkaz (ne chybu). Pokud klient vidí neočekávaně „starý" příkaz, klíč recykloval — generuj nový `idempotencyKey` na každý logický požadavek.

## Matice verzí tech stacku

Verze se řeší ze sdíleného `libs.versions.toml` / konvenčního pluginu `openbank.quarkus-service` a jsou za běhu vystaveny v `/api/v1/info` (autoritativní zdroj). Orientační stack:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x (sdílený katalog) |
| Quarkus | 3.x LTS (sdílený katalog) |
| JDK runtime | 21+ (Eclipse Temurin) |
| PostgreSQL | 16 |
| Kafka client | 3.x |

> Přesně připnuté verze nejsou natvrdo v `build.gradle.kts` této služby (dědí BOM a konvenční plugin) — přesná čísla čtěte z `/api/v1/info` nebo kořenového `libs.versions.toml`.

## Deploy / release

- **Release osa:** release-please vlastní `version.txt` (aktuálně `0.2.0`). Feature/fix PR **nesmí** ručně editovat `version.txt`; merge do `main` otevře per-service Release PR, který ji bumpne, zapíše changelog a otaguje `standing-order-service-v<version>`.
- **API kontraktová osa:** `openapi.yaml info.version` (`1.0.0`) se bumpuje nezávisle z OpenAPI diffu (ADR-0048), ne z typu commitu.
- **CI:** path-scoped per-service pipeline; integrační testy používají per-job Testcontainers (žádný sdílený compose stack).
