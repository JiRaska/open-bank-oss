# Scalability Targets

> Last updated: 2026-05-26
> Status: **v0.1** — design targets. Not yet measured against the reference implementation.
> All numbers are **engineering targets**; operator SLAs may differ.

## Reference workload profiles

We design for three named profiles. Operators pick the one matching their licensed activity and customer base.

| Profile | Customers | Daily payments | Peak TPS (payments) | Peak TPS (reads) | Concurrent sessions | Notes |
|---|---|---|---|---|---|---|
| **Sandbox** | 1 000 | 5 000 | 10 | 100 | 100 | Local dev, demos |
| **Tier-B EU retail bank** | 250 000 | 500 000 | 200 | 5 000 | 10 000 | Typical neobank or challenger bank year 1-3 |
| **Tier-A EU retail bank** | 5 000 000 | 10 000 000 | 4 000 | 100 000 | 200 000 | Established retail bank or top-tier challenger |

The reference implementation targets **Sandbox** for default deployment and **Tier-B** as the validated production target by M5. Tier-A is a stretch goal validated by M7.

## Latency budgets (Tier-B target)

End-to-end customer-perceived latency, measured at API Gateway:

| Operation | p50 | p95 | p99 | Hard timeout |
|---|---|---|---|---|
| Balance query (GET /accounts/{id}/balance) | < 50 ms | < 150 ms | < 300 ms | 2 s |
| Transaction list (last 30 days) | < 150 ms | < 400 ms | < 800 ms | 5 s |
| Domestic payment initiation (POST) | < 200 ms | < 500 ms | < 1 s | 5 s |
| SEPA Credit Transfer (synchronous accept) | < 300 ms | < 800 ms | < 1.5 s | 5 s |
| SEPA Instant Credit Transfer (full end-to-end) | < 3 s | < 7 s | < 9 s | 10 s (scheme hard limit) |
| Card authorisation (online) | < 50 ms | < 150 ms | < 300 ms | 2 s (scheme limit) |
| Login + SCA (full flow) | < 1 s | < 3 s | < 5 s | 10 s |
| Account opening (full KYC chain) | < 30 s | < 60 s | < 120 s | 5 min |
| PSD2 AISP /accounts | < 200 ms | < 500 ms | < 1 s | 5 s |
| PSD2 PISP /payments initiate | < 300 ms | < 800 ms | < 1.5 s | 5 s |

p99 budgets are **deployment SLOs**, not vendor commitments — the reference implementation may not meet them on first run.

### Latency budget decomposition example (domestic payment p99 = 1 s)

| Stage | Budget |
|---|---|
| API Gateway + WAF | 30 ms |
| Auth (Keycloak token validation, local cache) | 10 ms |
| Payment service ingress (validation, idempotency) | 50 ms |
| Saga step: party verification | 80 ms |
| Saga step: balance + reserve | 150 ms |
| Saga step: clearing dispatch | 200 ms |
| Saga step: outbox commit | 50 ms |
| Response serialisation | 20 ms |
| Network + retries headroom | 410 ms |
| **Total** | **1 000 ms** |

## Throughput targets (Tier-B)

| Service | Sustained TPS | Peak TPS (4x burst) | Notes |
|---|---|---|---|
| Account read | 5 000 | 20 000 | Cache-heavy |
| Balance read | 5 000 | 20 000 | Real-time projection |
| Transaction history read | 1 000 | 4 000 | DB pagination |
| Domestic payment write | 200 | 800 | Saga-bound |
| SEPA SCT write | 100 | 400 | Saga + clearing dispatch |
| SCT-Inst write | 50 | 200 | 10-second SLA constraint |
| Card auth | 500 | 2 000 | Scheme deadline 2s |
| PSD2 AISP | 400 | 1 600 | Per-consent quota controls peak |
| Audit event ingest | 50 000 | 100 000 | Kafka topic; no synchronous backpressure |

## Tier-A target (M7 stretch)

20x Tier-B for read paths, 20x for payment writes. Validated by:
- k6 load test sustained 30 min
- Chaos run during load test
- Multi-region active-active deployment
- Per-region capacity 50% of total (lose a region — degrade, do not fail)

## Horizontal scaling design

| Component | Scaling strategy | Limits |
|---|---|---|
| Stateless services (Quarkus) | K8s HPA on CPU + custom metrics (RPS, queue depth) | Min 3 / Max per-service-defined |
| Postgres OLTP | Vertical primary + read replicas; sharding by customer ID at M7 | Single primary handles Tier-B; shard at Tier-A |
| Postgres OLAP | Read-replica + columnar (DuckDB / Citus) at M5 | Read-only |
| Kafka brokers | Add brokers; partitions per topic sized for 10x burst | Topic partition count must be set at creation; over-partition |
| Redis | Cluster mode; consistent hashing | Use only for short-lived state |
| Object storage | Cloud-native (S3/GCS); effectively unbounded | Cost-bounded only |
| Keycloak | Cluster mode behind LB | DB-bound; cluster up to 8 nodes |

## Vertical baselines (per service pod, M3)

| Service category | CPU request / limit | Memory request / limit |
|---|---|---|
| Read-heavy (account, balance) | 250m / 1000m | 512Mi / 1Gi |
| Write-heavy (payment, ledger) | 500m / 2000m | 1Gi / 2Gi |
| Saga coordinators | 500m / 1500m | 1Gi / 2Gi |
| Event consumers (audit, notification) | 250m / 1000m | 512Mi / 1Gi |
| API gateway | 1000m / 4000m | 2Gi / 4Gi |
| Keycloak | 1000m / 4000m | 2Gi / 4Gi |

Native-compiled (GraalVM) builds reduce memory floors by ~60%; trial by M4.

## Database sizing (Tier-B baseline)

| Aspect | Target |
|---|---|
| Active connections per service | 20-50 (HikariCP) |
| Total connections to Postgres primary | < 500 (use PgBouncer if exceeded) |
| Query p99 | < 50 ms for OLTP queries |
| Long query alert threshold | > 1 s |
| Index hit ratio | > 99% on hot tables |
| Bloat alert threshold | > 20% on hot tables |
| Replication lag alert | > 1 s |
| Storage growth | < 100 GB / month per Tier-B deployment (excluding audit) |
| Audit storage | 7 years retention; ~500 GB / year for Tier-B |

## Kafka sizing (Tier-B baseline)

| Aspect | Target |
|---|---|
| Brokers | 3 minimum (multi-AZ); 5 for Tier-A |
| Per-topic partitions | min(num_consumers x 2, 32); over-partition for headroom |
| Replication factor | 3 |
| `min.insync.replicas` | 2 |
| Consumer lag alert | > 5 minutes of throughput |
| Throughput per broker | sustained 50 MB/s, peak 200 MB/s |
| Retention | banking topics 7-35 days; audit 90 days hot + cold archive 7 years |

## Caching strategy

| Layer | Cache | TTL | Invalidation |
|---|---|---|---|
| CDN | Static assets only | 1 year (hashed URLs) | Cache-busting via hash |
| API Gateway | Per-route, public data only | 1-60 s | TTL + manual purge |
| Service-level (Caffeine) | Reference data, lookup tables | 1-5 min | Event-driven invalidation via Kafka |
| Distributed (Redis) | Sessions, idempotency keys, rate limit counters | 1-72h | TTL |
| Read-through (Postgres) | Materialised views for balance | Real-time | Refreshed by transaction events |
| Browser | Bundle JS/CSS | 1 year (hashed) | Hash change |

**No caching of customer-specific financial data without explicit invalidation event.** Stale balances and stale transaction histories are unacceptable.

## Saga and queue depth budgets

| Saga | Acceptable steps p99 | Backlog alert | Stuck-saga alert |
|---|---|---|---|
| Account opening | 60 s | 100 in-flight | > 5 min stuck |
| Domestic payment | 2 s | 500 in-flight | > 30 s stuck |
| SEPA SCT | 5 s | 200 in-flight | > 1 min stuck |
| SCT-Inst | 7 s (scheme allows 10) | 50 in-flight | > 8 s stuck |
| Card authorisation | 1 s | 1 000 in-flight | > 2 s stuck (scheme limit) |

## Cost envelope (rough, Tier-B, cloud)

Not authoritative — depends entirely on cloud provider and operator efficiency. Order of magnitude for a Tier-B deployment on a major hyperscaler:

| Cost line | EUR / month |
|---|---|
| Compute (K8s, ~80 vCPU sustained) | 3 000 - 6 000 |
| Postgres managed (HA) | 1 500 - 4 000 |
| Kafka managed (3-broker) | 800 - 2 000 |
| Object storage + backups | 200 - 500 |
| Network egress | 500 - 2 000 |
| Observability (logs, metrics, traces) | 1 000 - 3 000 |
| Secret mgmt (Vault) | 200 - 500 |
| WAF + DDoS | 500 - 2 000 |
| **Total (rough)** | **7 700 - 20 000** |

Compare to a commercial core banking licence (EUR 100 k - 1 M / year minimum). The OSS distribution is the multiplier here.

## Verification methodology

| Test type | Trigger | Tool | Pass criteria |
|---|---|---|---|
| Microbenchmark | PR labelled `perf` | JMH | No regression > 5% |
| Endpoint load test | Nightly | k6 | p99 within budget; 0 errors |
| Burst test | Weekly | k6 | 4x sustained for 5 min; recovery within 30s |
| Soak test | Monthly | k6 | 1x sustained for 8h; no leak |
| Capacity test | Per milestone | k6 + manual | Find the breaking point; record |
| Chaos under load | Per milestone | k6 + Chaos Mesh | SLO held during AZ kill |

## Scalability anti-patterns (rejected)

- **Stateful service instances** — sessions in memory, no replication
- **Synchronous fan-out without circuit breaker**
- **Unbounded retries** — always cap + exponential backoff + jitter
- **N+1 queries** — caught by integration tests asserting query count
- **Per-request DB connections** — pool is mandatory
- **Per-request HTTP clients** — share clients
- **Unindexed search columns**
- **`SELECT *` on hot paths**
- **Polling for events** — use Kafka subscriptions
- **Cross-service synchronous chains > 3 hops** — convert to async saga

## Disclaimer

These targets are aspirational engineering numbers, not vendor commitments. The reference implementation has not yet been benchmarked. Real performance depends on operator hardware, configuration, and workload mix.
