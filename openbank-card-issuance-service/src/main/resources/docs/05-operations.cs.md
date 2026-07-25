# Provoz

## Build & běh

```bash
# Build (fast-jar — nikdy uber-jar)
./gradlew :openbank-card-issuance-service:quarkusBuild

# Dev režim (live reload; OIDC vypnuté v %dev)
./gradlew :openbank-card-issuance-service:quarkusDev

# Generický infra build/push
openbank-infra/scripts/build-push-service.sh card-issuance-service
```

Služba používá sdílenou Gradle konvenci `openbank.quarkus-service`; Dockerfile staví **fast-jar** (`quarkus-app/`), dle pravidla platného v celém repu.

## Endpointy & porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/cards/...` | 8118 | byznys REST API |
| `/api/docs` | 8118 | Swagger UI |
| `/api/v1/info` | 8118 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

**Management rozhraní je povolené na samostatném portu 8085** (`quarkus.management.enabled`, `root-path: /q`); aplikace naslouchá na **8118**.

## Konfigurace

| Konfigurace | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **MUSÍ být přepsáno v prod přes Vault** |
| reaktivní datasource URL | `postgresql://localhost:5432/openbank_cards` | PostgreSQL |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **přepsat v prod** |
| OIDC auth-server | `http://localhost:8080/realms/openbank` | issuer |
| Kafka bootstrap | `localhost:29092` | brokeři |
| Redis hosts | `redis://localhost:6379` | Valkey/Redis |
| OTel OTLP endpoint | `http://localhost:4317` | tracing |
| `openbank.rate-limit.max-concurrent-requests` | `200` | strop souběžnosti |
| `openbank.card.pan-vault-backfill.enabled` | `true` | při startu vygeneruje syntetický PAN pro neterminální karty vydané před trezorem (`pan_encrypted IS NULL`) a zachová jim zobrazené poslední 4 číslice. Idempotentní, neblokující a nikdy neshodí start; při každém startu zaloguje jednu souhrnnou řádku `[pan-vault-backfill]` |

Bezpečnostní hlavičky (`X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS atd.) jsou globálně nastaveny v `application.yaml`. Logy jsou JSON v ne-dev profilech.

## Health checky

- **Liveness:** `/q/health/live`
- **Readiness:** `/q/health/ready` — podpořeno SmallRye Health (dostupnost DB / Kafka producer)

Proby míří na management port **8085**.

## Serverless / workload tier (ADR-0057)

Dle [ADR-0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md) (scale-to-zero workload tiery + FinOps klasifikátor) je card-issuance nízkoprovozní, request-driven služba a je kandidátem na scale-to-zero / scale-from-zero tier. **Omezení:** outbox dispatcher běží na `@Scheduled` ticku (každých 5s) a Deployment je připnut na `replicas: 1` kvůli pořadí jediného zapisovatele (ADR-0050 N4) — jakákoli scale-to-zero politika tedy musí ponechat přesně jednu repliku, dokud existuje nedoručená outbox práce. Autoritativní klasifikaci tieru odvozuje FinOps klasifikátor, nedeklaruje se zde.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="card-issuance-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds` |
| Latence p95 POST (vydání) | < 300 ms | zahrnuje zápis do DB + insert outboxu |
| Zpoždění outboxu | < 10 s | dispatcher běží každých 5s; stáří čekajícího řádku |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Roste zpoždění outboxu

1. Spočítat nedoručené řádky: `SELECT count(*) FROM card_outbox WHERE status='PENDING'`.
2. Zkontrolovat dostupnost Kafky z podu (publisher je circuit-broken: opakovaná selhání otevřou breaker na ~5s).
3. Tailovat logy dispatcheru: `kubectl logs -l app=card-issuance-service | grep CardOutboxDispatcher`.
4. Prozkoumat řádky `FAILED`/`DEAD`: `SELECT event_id, attempt_count, last_error FROM card_outbox WHERE status IN ('FAILED','DEAD')`.

### Flyway checksum mismatch při startu

Nikdy neupravujte aplikovanou migraci. Pokud checksum mismatch blokuje start, nastavte `QUARKUS_FLYWAY_REPAIR_AT_START=true` v gitops env, nechte ustálit, pak odeberte. (`validate-on-migrate` je zde již `false`.)

### Vydání nečekaně vrátí existující kartu

To je záměr: `Idempotency-Key` se shodoval s existujícím `cards.idempotency_key`. Pro skutečně novou kartu použijte čerstvý klíč.

## Testování & CI

- Unit: `CardTest` (stavový automat), `CardServiceTest`, `CardOutboxDispatchTest`.
- Integrační: `CardOutboxDispatchIT` s `PostgresRedisTestResource` — izolovaný PostgreSQL + Valkey na test JVM přes Testcontainers (CI infra sweep #578); Kafka je in-memory. Scheduler je pod `%test` vypnutý, takže IT řídí `dispatchScheduledBatch()` explicitně.
- Governance: `HibernateSequenceGuardTest` hlídá konvenci `<table>_seq`.
- Pokrytí je ratchet-only (kover); card-issuance **není** money-path, takže vyšší money-path floor neplatí.

## Deploy / release

- Verzování: per-service `version.txt` (aktuálně `0.3.0`), vlastněné release-please z Conventional Commits. Needitovat ručně.
- CD: ArgoCD přebírá nový image tag z gitops manifestů.
