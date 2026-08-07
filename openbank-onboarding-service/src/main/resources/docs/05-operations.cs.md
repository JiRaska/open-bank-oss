# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar)
./gradlew :openbank-onboarding-service:quarkusBuild

# Dev mód (live reload; OIDC vypnuté v %dev)
./gradlew :openbank-onboarding-service:quarkusDev

# Docker image (multi-stage; fast-jar, JDK 25 Temurin, ZGC)
openbank-infra/scripts/build-push-service.sh onboarding-service
```

Image staví `.github/workflows/Dockerfile.deploy` z fast-jaru sestaveného na hostu a běží na `eclipse-temurin:25-jre` (glibc, #3354) jako non-root uživatel `openbank`, entrypoint `java -XX:+UseZGC … -jar /app/quarkus-run.jar`, port 8130. `openbank-onboarding-service/Dockerfile` nic nestaví (#3016) — `docker build` proti němu selže, kontext neobsahuje `quarkus-app/`; pipeline z něj čte jen `EXPOSE`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/onboarding/records` | 8130 | čtecí API výpis / detail |
| `/api/v1/onboarding/funnel` | 8130 | KPI počty dlaždic |
| `/api/v1/info` | 8130 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8130 | Swagger UI |
| `/q/openapi` | 8130 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Je zapnuté samostatné **management rozhraní** (`quarkus.management.enabled=true`, port `8085`, root-path `/q`), takže health, metriky a docs endpoint jsou servírovány na management portu, mimo business API na 8130.

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **MUSÍ být přepsáno v produkci** (Vault, ADR-0017) |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | client secret Keycloaku — **MUSÍ být přepsáno v produkci** |
| (URL datasource) | `postgresql://localhost:5432/openbank_onboarding` | reaktivní + JDBC (Flyway) datasource |
| (kafka bootstrap) | `localhost:29092` | `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` |
| (oidc auth server) | `http://localhost:8080/realms/openbank` | OIDC issuer |
| (otel endpoint) | `http://localhost:4317` | OpenTelemetry OTLP exporter |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build identifikace v `/api/v1/info` |

Placeholder hesla (`CHANGE_ME_LOCAL_DEV_ONLY`) jsou jen pro dev a musí být v jakémkoli ne-dev prostředí dodána z platformového úložiště tajemství.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC.
- **Readiness:** `/q/health/ready` — reaktivní PostgreSQL spojení + Kafka.

Logy jsou JSON do konzole (`quarkus.log.console.json=true`) mimo `%dev`; trasování přes OpenTelemetry OTLP, metriky přes Micrometer/Prometheus.

## Serverless / workload tier (ADR-0057)

Tato služba je **read-model s nárazovým, operátorem řízeným provozem** (cockpit se používá v pracovní době) a je krmená událostmi, ale tolerantní ke cold startům (konzumenti pokračují od `earliest`). Je dobrým kandidátem na **scale-to-zero / scale-from-zero tier** dle FinOps klasifikátoru ADR-0057 — přesné přiřazení tieru je **TBD** (řízeno klasifikátorem, ne nastavováno ručně zde). Kompromis: cold start přidá latenci prvnímu dotazu cockpitu a krátké dohnání consumer lagu.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost (pracovní doba) | 99,9 % | Prometheus `up{service="openbank-onboarding-service"}` |
| Latence p95 GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Lag projekce (událost → viditelný řádek) | < 5 s | consumer lag Kafky na 3 skupinách |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Consumer lag / cockpit ukazuje stará data

1. Zkontrolujte consumer-group lag pro `onboarding-service-party`, `onboarding-service-kyc`, `onboarding-service-sca`.
2. Zkontrolujte logy podu na poison-pill chyby `OnboardingEventConsumer` (`grep "Failed to parse"` / `"Projection failed"`). Ty jsou acknowledgnuté, ne retry — špička znamená změnu schématu producenta.
3. Pokud lag přetrvává, ověřte dostupnost Kafka brokeru a shodu názvů topiků (`openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`).

### Read-model vypadá špatně / potřebuje rebuild

Protože je tabulka čistá projekce: zastavte službu, truncate `onboarding_records`, resetněte offsety tří consumer-group na `earliest` a restartujte. Projekce se znovu naplní ze zdrojového logu událostí. Není potřeba koordinace se zápisy party/kyc/sca (read-only).

### Záznamy se nikdy neukládají (čerstvá DB)

Symptom: události konzumovány, ale `onboarding_records` zůstává prázdná s `relation "onboarding_records_seq" does not exist` v logu. Příčina: neaplikovaná Flyway V2. Náprava: zajistěte, že proběhly V1 i V2 (`migrate-at-start: true`); V2 vytváří Hibernate id sekvenci. Viz [04 — Data](./04-data.md).

### DB / startovací retry spojení

Flyway je nakonfigurováno s `connect-retries: 10` v 2s intervalech, takže pomalu startující PostgreSQL je při bootu tolerován.

## Matice verzí tech stacku

V payloadu `/api/v1/info`:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x (toolchain projektu) |
| Quarkus | 3.x (`enforcedPlatform(libs.quarkus.bom)`) |
| JDK runtime | 25 (Eclipse Temurin, ZGC) |
| PostgreSQL | 16 |
| Perzistence | Hibernate Reactive + Panache (reaktivní PG klient) |
| Messaging | SmallRye Reactive Messaging (Kafka) |

## Deploy / release

- **Released komponenta:** má `version.txt` (aktuálně `0.2.0`); release axis vlastní release-please. Neupravujte ručně `version.txt` ani changelog.
- **Osa API kontraktu:** `openapi.yaml:info.version` (`1.0.0`), nezávislá na release verzi (ADR-0048).
- **CI:** path-scoped per-service pipeline; fast-jar build, build/push image, ArgoCD vyzvedne tag.
- **Přísnost review:** ačkoli onboarding-service nedrží peníze ani stav systému záznamu, ADR-0068 ji řadí pod **money-path review rigour** (2 schválení + threat model), protože je KYC-decision-adjacent.
