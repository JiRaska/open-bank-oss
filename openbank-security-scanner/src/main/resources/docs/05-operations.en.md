# Operations

## Build & run

```bash
# Build (locally)
./gradlew :openbank-security-scanner:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-security-scanner:quarkusDev

# Docker (from openbank-infra/)
docker compose build security-scanner
docker compose up -d security-scanner
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/security/...` | 8120 | Security scan REST API |
| `/api/v1/ict-incidents/...` | 8120 | ICT incident management API |
| `/api/v1/info` | 8120 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8120 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8120 | OpenAPI spec |
| `/q/swagger-ui` | 8120 | Swagger UI (dev only) |
| `/q/health` | 8120 | liveness + readiness |
| `/q/metrics` | 8120 | Prometheus |

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | DB host (in docker: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | DB password — **MUST be overridden in prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.securityscanner=DEBUG` |

### Scanner service list (application.yaml)

```yaml
openbank:
  security-scanner:
    scan-interval-minutes: 30
    services:
      - name: account-service
        url: http://account-service:8100
        port: 8100
      - name: sanctions-service
        url: http://sanctions-service:8123
        port: 8123
      # ... 25 more services
```

To add a new service to the scan list, add an entry to `openbank.security-scanner.services` in the service config and deploy.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer.

Note: Redis is NOT a dependency of this service (no idempotency store used). The DB connection is
checked but holds no business data — see [04 — Data](./04-data.md).

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8120 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8120 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.5% (lower than money-path — not customer-facing) | `up{service="security-scanner"}` |
| Scheduled scan completion | < 90 s for 27 services | `security_scan_duration_seconds` |
| Latency p95 GET /report | < 50 ms | in-memory cache |
| ICT incident API p95 | < 200 ms | in-memory write + direct Kafka emit |

## Runbooks

### Scheduled scan not running

1. Check scheduler logs: `kubectl logs -l app=security-scanner | grep scheduledScan`
2. Verify service is running: `kubectl get pod -l app=security-scanner`
3. Trigger manually: `POST /api/v1/security/scan`
4. Check config: `openbank.security-scanner.scan-interval-minutes` must be > 0.

### Service showing as unreachable in report

Symptom: `reachable: false`, grade `F` for a service.

1. Verify the service is running: `kubectl get pod -l app={service-name}`
2. Verify the URL in `openbank.security-scanner.services` config is correct.
3. Check network policy — scanner must have egress to all service ports.
4. The management port (8085) probe falls back to API URL — if neither responds, the service is truly down.

### All services showing grade F (CRITICAL)

Likely a network policy or DNS issue.

1. Check pod logs for Java exceptions: `kubectl logs -l app=security-scanner | grep "java.net"`
2. Verify DNS from scanner pod: `kubectl exec -it <scanner-pod> -- nslookup account-service`
3. Verify egress policy allows scanner to reach cluster services.

### ICT incident event missing downstream

Incidents are emitted straight to Kafka with no outbox, so a failed publish leaves no local record
to retry from (#4709).

1. Check the emitter for errors: `kubectl logs -l app=security-scanner | grep ict-incident-events-out`
2. Check the broker: `kcat -L -b kafka:9092`
3. Confirm the topic received it: read the tail of `openbank.security.ict.incident`.
4. If the publish was lost, re-report the incident through `POST /api/v1/ict-incidents`.

### Scan results empty after a restart

Expected, not a fault: scan state is in-memory only. A restarted pod serves an empty report until
the first scheduled scan completes (2 minutes after startup, then every 30 minutes). Trigger
`POST /api/v1/security/scan` to fill it immediately. In-flight ICT incidents do NOT come back.

### P1_CRITICAL ICT incident — regulatory reporting

Timeline (DORA Art. 17):
1. **T+0** — incident detected, `POST /api/v1/ict-incidents` with `severity=P1_CRITICAL`
2. **T+4h** — initial report to CNB; `POST /api/v1/ict-incidents/{id}/regulatory-report` with `regulatoryReportId`
3. **T+24h** — intermediate report to CNB (if not yet resolved)
4. **T+resolved** — final report to CNB; `PATCH /status` with `status=RESOLVED` + rto/rpo

## Tech-stack version matrix

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| Gradle | 9.5.1 |
| PostgreSQL driver | 42.7.x |
| Kafka client | 3.7.x |

## Deploy / release

Per-service CI pipeline (`.github/workflows/ci-security-scanner.yml`):

1. `./gradlew :openbank-security-scanner:test` — unit + integration tests
2. `./gradlew :openbank-security-scanner:quarkusBuild` — fast-jar build
3. CycloneDX SBOM generation
4. Docker image build → push to registry
5. CD: ArgoCD picks up the new tag from the GitOps manifest

## Local dev notes

The scanner probes services by URL — in dev mode, the service list should be overridden in `application.yaml` to point to locally running services. Alternatively, run with `QUARKUS_PROFILE=test` where the service list is empty and scans produce empty reports.
