# Operations

## Build

```
./gradlew :openbank-audit-service:build           # kompilace + testy
./gradlew detekt ktlintCheck koverVerify build    # lokální gate před PR
```

Build je fast-jar (nikdy uber-jar — viz GitOps pravidla v root CLAUDE.md). Generický image build přes `openbank-infra/scripts/build-push-service.sh openbank-audit-service` (host-side `quarkusBuild`, pak Docker COPYne `quarkus-app/`).

- **Release osa:** `version.txt` (aktuálně `0.3.0`), vlastní release-please z Conventional Commits — neupravuj ručně.
- **API osa:** `openapi.yaml: info.version` (`1.0.0`); major == `openbank.api.version` == `/api/v1` (ADR-0048).

## Runtime konfigurace

| Oblast | Hodnota | Zdroj |
|---|---|---|
| App HTTP port | `8113` | `quarkus.http.port` |
| Management port | `8085`, root-path `/q` | `quarkus.management.*` |
| Datasource | `postgresql://localhost:5432/openbank_audit` | `quarkus.datasource` |
| Flyway | `migrate-at-start: true`, 10 connect retries | `quarkus.flyway` |
| Kafka bootstrap | `localhost:29092` (lokálně) | `quarkus.smallrye-reactive-messaging.kafka` |
| Vstupní kanál | `audit-events-in`, group `audit-service`, `auto.offset.reset=earliest` | `mp.messaging.incoming` |
| OIDC | `…/realms/openbank`, client `openbank-services` | `quarkus.oidc` |
| OTel | OTLP `http://localhost:4317` | `quarkus.otel` |
| Rate limit | zapnuto, max 200 concurrent | `openbank.rate-limit` |

Secrets (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) nesou placeholdery `CHANGE_ME_LOCAL_DEV_ONLY`; produkce injektuje reálné hodnoty (Vault, ADR-0017). Placeholdery nikdy nešipuj.

## Serverless / workload tier (ADR-0057)

audit-service je event consumer se stálou, low-latency ingest povinností a 10letým durability mandátem. **Není** dobrý kandidát na scale-to-zero: studený consumer by zaostával za audit streamem a riskoval zmeškání/zpoždění regulované evidence. Klasifikuj jako workload **always-on (warm) tier** podle [ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md); samotné read API by scale-down sneslo, ale Kafka consumer by měl zůstat rezidentní. (Přesný štítek tieru se nastavuje v konfiguraci FinOps klasifikátoru, ne v této službě.)

## Health probes

SmallRye Health je na management portu:

- **Liveness:** `GET :8085/q/health/live`
- **Readiness:** `GET :8085/q/health/ready` — zahrnuje datasource a Kafka konektivitu.

Metriky: `GET :8085/q/metrics` (Prometheus). Docs: `:8085/q/openbank/docs` (tato dokumentace). Tracing: OTLP do collectoru.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Indikátor | Cíl (návrh) |
|---|---|
| Ingest lag (událost → uloženo) | p99 < 5 s při nominální zátěži |
| Latence read API `GET /entries/{id}` | p99 < 300 ms |
| Dostupnost (read API) | 99,9 % |
| Durability (žádné ztracené audit události) | 100 % — kryto `earliest` replay + neměnný store |
| RTO / RPO | RTO 15 min / RPO 0 pro commitnuté řádky (Kafka replay kryje in-flight) |

## Runbooky

### Consumer lag / události se neobjevují
1. Zkontroluj readiness na `:8085/q/health/ready` (běží Kafka?).
2. Prohlédni lag consumer group `audit-service` na brokeru.
3. Logy jsou JSON (`quarkus.log.console.json`); `AuditConsumer` loguje `Failed to record audit entry` s prvními 200 znaky případného poison payloadu — hledej to. Poison messages jsou spolknuty (offset postoupí), takže trvalé mezery ukazují na parse/DB chybu, ne na back-pressure.
4. Pro backfill po výpadku určuje dosah replay `auto.offset.reset=earliest` consumeru plus retention topiku.

### DB / migrace
- `migrate-at-start` spustí Flyway při bootu. Při checksum mismatch (migrace změněna po aplikaci) dočasně nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true`, pak odeber, jakmile se DB usadí (Flyway pravidlo v root CLAUDE.md). Aplikovanou migraci nikdy nepřepisuj.
- `relation "<table>_seq" does not exist` ⇒ chybí V4 sekvence; spusť migrace znovu.

### Překvapení z neměnnosti
- `UPDATE`/`DELETE` nad `audit_entries` vracející "0 rows affected" je **očekávané** — pravidla `DO INSTEAD NOTHING` tiše no-op. Nepokoušej se "opravit" data na místě; místo toho připoj kompenzační záznam.

## Deploy

GitOps přes ArgoCD (manifesty v `gitops/`). Při merge-konfliktu image tagů ber `--ours` pro image řádky (root CLAUDE.md). Commity musí být podepsané GPG-registrovaným e-mailem.
