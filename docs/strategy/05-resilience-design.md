# Resilience Design

> Last updated: 2026-05-26
> Status: **v0.1** — target resilience posture. Implementation status tracked per service.
> Frameworks: **DORA Art. 11-12** (response, recovery, backup), **DORA RTS 2024/1774** (ICT risk management), industry SRE practice.

## Resilience principles

1. **Assume failure** — Every dependency will fail; design for graceful degradation, not for uptime miracles.
2. **Isolate blast radius** — One failing module must not bring down the bank.
3. **Make recovery routine** — Restore from backup quarterly; failover semi-annually; drill until boring.
4. **Observe to operate** — If you cannot see it, you cannot recover it.
5. **Default to safety** — When in doubt, refuse the operation rather than corrupt state.

## Failure domains

OpenBank is designed around explicit failure domains:

| Domain | Scope | Isolation mechanism |
|---|---|---|
| Service instance | Single pod | K8s replicas, HPA |
| Service | All pods of one service | Bulkhead — own DB connection pool, own thread pool, own Kafka consumer group |
| Database | One Postgres cluster | One Postgres cluster per service (or per group of co-located services) |
| Availability zone | One AZ in a region | Multi-AZ deployment, anti-affinity rules |
| Region | One cloud region | Multi-region deployment for Tier-1 services (active-passive M6, active-active M7) |
| Cloud provider | One CSP | Cloud-agnostic packaging; multi-CSP optional for sovereign deployments |
| Human operator | One engineer's mistake | RBAC, just-in-time elevation, change windows, rollback automation |
| Supply chain | One compromised dependency | Pinned hashes, SBOM, cosign signing, base image scanning |

## RTO / RPO targets per service tier

Recovery Time Objective (RTO) and Recovery Point Objective (RPO) define how fast and how completely a service must recover after a disaster.

| Tier | Examples | RTO | RPO | Notes |
|---|---|---|---|---|
| **Tier 0 — Critical payments** | sepa-instant, domestic-payment, card-authorization | 15 min | 0 (zero data loss) | Synchronous cross-AZ replication; multi-region active-active by M7 |
| **Tier 1 — Core ledger** | ledger, transaction, balance, account | 30 min | < 1 min | Synchronous primary replica + async cross-region |
| **Tier 2 — Customer-facing** | sepa-payment, party, kyc, consent, notification | 1 hour | < 5 min | Async cross-region replication |
| **Tier 3 — Operational** | audit, interest, reporting, dispute, standing-order | 4 hours | < 15 min | Daily snapshot + WAL shipping |
| **Tier 4 — Analytical** | data lake, BI exports | 24 hours | < 1 hour | Best-effort; recoverable from upstream sources |

These are **operator targets**; the reference implementation provides primitives (replication, snapshots, runbooks), not the operational SLA itself.

## Resilience patterns adopted

### Pattern: Outbox

Problem — A service must update its database AND publish a Kafka event atomically.
Solution — Write the event to an `outbox` table in the same DB transaction; a separate process (Debezium CDC or polling) reads the outbox and publishes to Kafka.
Mandate — **Every service publishing Kafka events MUST use outbox.** Inline `kafkaTemplate.send()` calls are an anti-pattern and a code review blocker.
Reference — `openbank-sepa-payment` (only existing implementation; to be replicated across all services in M2-M3).

### Pattern: Idempotency keys

Problem — Network retries cause duplicate POSTs.
Solution — Every write endpoint accepts an `Idempotency-Key` header (UUIDv4); the service stores the key + response for 24-72h and returns the cached response on replay.
Mandate — All POST/PUT/PATCH endpoints on external APIs MUST accept idempotency keys; internal service-to-service writes SHOULD.

### Pattern: Saga

Problem — Multi-service transactions cannot use XA / 2PC at scale.
Solution — Decompose into local transactions with explicit compensation; either orchestrated (saga coordinator) or choreographed (event-driven).
Mandate — All cross-service write workflows (e.g. account opening = party + kyc + account + notification) MUST use a saga; ad-hoc multi-call workflows are blocked.
Status today — Zero sagas in repo; M2 milestone.

### Pattern: Circuit breaker

Problem — A slow downstream cascades into upstream thread exhaustion.
Solution — Resilience4j circuit breaker around every synchronous external call.
Configuration baseline:
- Failure rate threshold: 50% over sliding window of 20 calls
- Wait duration in open state: 30 seconds
- Slow call rate threshold: 100% over 1 second
- Half-open: 5 permitted calls

### Pattern: Bulkhead

Problem — One slow downstream consumes all available threads/connections.
Solution — Per-downstream connection pool and thread pool; sized for expected concurrency, not for theoretical maximum.
Mandate — Each service declares per-downstream pool sizes; the framework rejects unconfigured downstreams.

### Pattern: Rate limiting and quotas

Problem — A single client or upstream service drowns the system.
Solution — Token-bucket rate limiter at API gateway (per client, per endpoint, per IP) AND at service ingress (defence in depth).
Defaults:
- TPP per-AISP: 4 requests / day per consent (per PSD2 RTS)
- Customer mobile: 60 requests / minute
- Internal service: configured per dependency

### Pattern: Backpressure

Problem — A Kafka producer outpaces its consumers; lag grows unbounded.
Solution — Bounded consumer queue + producer pause when consumer lag exceeds threshold; alert when lag > 5 min of throughput.

### Pattern: Timeout budget

Problem — Sync chains without timeouts cause request stalls.
Solution — Every synchronous call has an explicit timeout; total request budget propagated via OpenTelemetry baggage.
Default budget: 2s for customer-facing; 5s for operator UI; 200ms for inter-service east-west.

### Pattern: Health and readiness

- `/health/live` — pod is up; restart if failing
- `/health/ready` — pod can accept traffic; drain from LB if failing
- `/health/startup` — slow-start checks; do not kill until startup probe passes

### Pattern: Graceful shutdown

- SIGTERM → stop accepting new requests, drain in-flight, close DB pools, commit outstanding Kafka offsets, exit cleanly within 30 seconds
- K8s `terminationGracePeriodSeconds: 60`

## Multi-AZ and multi-region

### Multi-AZ (M3 baseline)

- All Tier 0-2 services: minimum 3 replicas across 3 AZs
- Pod anti-affinity rules: `topologySpreadConstraints` with `maxSkew: 1`
- Postgres: synchronous replication to standby in different AZ
- Kafka: `min.insync.replicas=2`, `replication.factor=3`, replicas spread across AZs

### Multi-region (M6: active-passive; M7: active-active)

**Active-passive (M6):**
- Primary region serves all traffic
- Secondary region: cold standby with continuous replication
- Failover: DNS-level (Route 53 / Cloud DNS) + manual approval
- Target RTO 15-30 min, RPO 1-5 min (async replication)

**Active-active (M7):**
- Both regions serve traffic, split by data residency / customer affinity
- Per-customer pinning to a "home" region; cross-region requests proxied
- Read replicas in all regions; writes go to customer's home region
- Conflict resolution: customer identity is a hard partition; financial transactions are append-only per ledger account
- Requires careful saga design across regions

## Chaos engineering programme

**M3:** Manual game days quarterly — kill a pod, sever a network, fail an AZ in staging.

**M4:** Chaos Mesh / LitmusChaos automated experiments in staging:
- Pod kill, network latency injection, DNS chaos, disk fill, CPU pressure
- Run during business hours with on-call engaged

**M5:** Production chaos in lowest-risk paths (notification service first), expanding outward.

**M6+:** Continuous chaos with steady-state hypotheses verified by SLO probes.

Never break customer money flows in production chaos. Tier 0 services are excluded from production chaos until at least M7.

## Backup strategy

| Asset | Method | Frequency | Retention | Encryption | Test restore |
|---|---|---|---|---|---|
| Postgres OLTP | Continuous WAL + nightly base backup | WAL: continuous; base: 24h | 35 days hot; 7 years cold | KMS-managed | Quarterly |
| Postgres OLAP | Daily logical dump | 24h | 90 days | KMS-managed | Quarterly |
| Kafka topic retention | Per-topic config; banking topics 7-35 days | Continuous | 35 days (banking), 90 days (audit), 7 years (regulatory) | Encrypted at rest | Quarterly replay test |
| Configuration (K8s manifests, Helm values) | Git | Continuous | Indefinite | Repo encryption | Restore in disaster drill |
| Secrets (Vault) | Vault snapshot | Daily | 30 days | KMS-managed | Quarterly |
| Object storage (statements, exports) | Versioning + cross-region replication | Continuous | 7 years (regulatory) | KMS-managed | Annual |

## Incident response

**Severity ladder:**

| Sev | Definition | Response time |
|---|---|---|
| Sev-1 | Customer money loss or systemic outage | Page on-call immediately; comms within 15 min |
| Sev-2 | Major degradation; SLA breach in progress | Page on-call within 15 min; comms within 1h |
| Sev-3 | Localised issue, workaround available | Acknowledge within 1h; fix in next sprint |
| Sev-4 | Cosmetic or non-customer-facing | Normal queue |

**Post-incident review:** Blameless within 72h of resolution; published internally; learnings tracked as actions with owners.

**DORA major-incident reporting (Art. 17-23):** Operators must report to CNB within the regulatory windows (initial: 4h; intermediate: 72h; final: 30 days). The repository provides a template (`docs/runbooks/dora-incident-template.md` — to be created).

## Resilience verification — CI and operational gates

| Gate | Cadence | Tool |
|---|---|---|
| Unit + integration tests pass | Every PR | JUnit, Testcontainers |
| Load tests within budget | Nightly | k6 |
| Chaos experiments pass | Weekly in staging | Chaos Mesh |
| Backup restore drill | Quarterly | Manual + runbook |
| DR failover drill | Semi-annual | Manual + runbook |
| TLPT (Threat-Led Pen-Test) | Annual | External provider |

## Out of scope (operator responsibility)

- Data centre physical resilience (power, cooling, fire)
- Telecom redundancy
- Regulator notification logistics (templates provided; operator submits)
- Communication channels with customers during incidents (operator branding)

## Disclaimer

These are target resilience patterns. The reference implementation now has fleet-wide outbox dispatch and live saga/Temporal orchestration (ADR-0101); the remaining gap is a formal chaos-engineering programme (milestones M2-M5).
