# ADR-0077 — Observability Three-Pillar Strategy for OpenBank

**Status:** Accepted  
**Delivery-Status:** Partial
**Date:** 2026-06-10  
**Author:** @JiRaska  
**Relates to:** ADR-0008 (OpenTelemetry), ADR-0029 (Governance as Code), ADR-0054 (FinOps)

> **Amendment 2026-06-19 — implementation complete (non-money-path; money-path pending approvals).**
>
> - **Tier C domain metrics** (business counters/gauges): fleet sweep shipped in 26 PRs (#789–#815)
>   + #788 via `DomainMetrics` per-service pattern (ADR-0079). The 7 money-path services (ledger,
>   balance, transaction, sepa-payment, domestic-payment, sepa-instant, fx) await the 2-approval
>   gate per ADR-0030 — tracked in issue JiRaska/open-bank#787.
> - **Pillar 2 traces**: OTel SDK enabled fleet-wide (QUARKUS_OTEL_SDK_DISABLED removed); native-image
>   auto-instrumentation pilot on product-catalog (ADR-0083, PR #1021).
> - **Alertmanager** wired (GoAlert/ntfy last-mile): ADR-0088 D1.
> - ADR-0087 (Correlation & Profiling Layer) and ADR-0088 (Extension: on-call, SLO-as-code, durable
>   retention, mobile RUM) extend this ADR for the operational edges not covered here.

> **Amendment 2026-07-01 — Phase 3 structured logging shipped.**
>
> - **Phase 3 (structured JSON logs):** ✅ Shipped — `META-INF/microprofile-config.properties`
>   added to `openbank-libs-runtime` (PR #31) with shared JSON log format; fields: `traceId`,
>   `spanId` (OTel), `correlationId`, `requestId` (CorrelationIdRequestFilter). SmallRye Config
>   picks this up from the library JAR at lower priority than service-level `application.yaml`,
>   so the ~29 services without any log format automatically get structured JSON logging; the 4
>   services with a custom format (`account`, `psd2`, `sca`, `agent`) keep their own value.
>   Dev-profile override (plain text) also centralised in the same file.
> - **Remaining:** Phase 4 alerting (Alertmanager already wired per ADR-0088 D1); money-path
>   domain metrics (issue JiRaska/open-bank#787, 2-approval gate).

---

## Context

OpenBank runs ~28 Quarkus microservices on EKS. A payment instruction crosses at minimum 5 services
(party → sca → sepa-payment → ledger → clearing). A KYC flow crosses 4. An FX conversion crosses 6.

An audit of the current observability stack (2026-06-10) revealed:

| Pillar | Status | Finding |
|--------|--------|---------|
| Metrics — infrastructure | ✅ Working | kube-prometheus-stack scrapes nodes, JVM, Kafka, DB pools |
| Metrics — business | ❌ Missing | No domain counters. No payment counts, no account totals, no KYC verdicts |
| Metrics — HTTP | ⚠️ Partial | PodMonitor just added. Only `/q/info` and `/q/config` traffic visible (sandbox idle) |
| Traces | ❌ Dead | Tempo deployed but `QUARKUS_OTEL_SDK_DISABLED=true` on all services |
| Logs | ⚠️ Unstructured | Promtail collects stdout but no correlation ID, paymentId, amount as structured fields |
| Alerting | ❌ Off | Alertmanager disabled. Zero SLOs, zero alert rules |
| Outbox observability | ❌ Blind | Outbox queue depth and processing lag are not measured |

### Why this matters more for a bank than for a typical SaaS

1. **Regulatory audit trail** — PSD2 / EBA requires demonstrating that payment instructions were
   processed within defined SLAs. Without metrics and traces, this cannot be proved.
2. **Money stuck = money lost** — A payment stuck in the outbox for 4 hours is a customer complaint,
   a potential regulatory breach (SCT Inst ≤ 10 s end-to-end), and a reconciliation nightmare.
3. **Cross-service debugging** — Without distributed traces, a failed payment requires manually
   correlating logs across 5+ services, namespaces, and Kafka topics. This is not operationally
   viable at any scale.
4. **SLO evidence** — Regulators and auditors ask for SLO compliance reports. These cannot be
   produced from infrastructure metrics alone.

---

## Decision

Implement a three-pillar observability strategy in four phases. Each phase is independently
shippable and independently valuable.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Grafana (single pane)                         │
│  Business Dashboards │ Service Health │ Traces │ Logs │ Alerts       │
└──────────┬──────────────────┬──────────────┬──────────┬─────────────┘
           │                  │              │          │
    Prometheus            Prometheus       Tempo      Loki
    (metrics)             (metrics)       (traces)   (logs)
           │                  │              │          │
    ┌──────▼──────┐    ┌──────▼──────┐      │    ┌────▼─────┐
    │Infrastructure│    │   Business  │      │    │Structured│
    │  (existing)  │    │   Metrics   │      │    │  Logs    │
    └─────────────┘    │  (new)      │    OTLP   └──────────┘
                        └─────────────┘      │
                                       ┌─────▼──────────┐
                                       │ OpenTelemetry  │
                                       │ Auto-instr.    │
                                       │ (Quarkus OTEL) │
                                       └────────────────┘
```

### Pillar 1 — Metrics

**Tier A — Infrastructure** (existing, no action needed)
- Kubernetes cluster, node, pod, JVM, Kafka broker, DB pool metrics via kube-prometheus-stack

**Tier B — HTTP observability** (partially done — PodMonitor added 2026-06-10)
- `http_server_requests_seconds_{count,sum,bucket}` per service, endpoint, method, status
- Enables: request rate, error rate %, P99 latency per endpoint

**Tier C — Domain/Business metrics** (NEW — requires service instrumentation)

Each service registers Micrometer counters and gauges at startup via a shared
`openbank-libs` module (`libs/observability/`). Convention:

```kotlin
// Counter — increment on domain event
Counter.builder("openbank.payments.submitted")
    .tag("type", paymentType)   // sepa | domestic | instant | fx
    .tag("currency", currency)
    .register(meterRegistry)
    .increment()

// Timer — measure processing duration
Timer.builder("openbank.payment.processing.duration")
    .tag("type", paymentType)
    .tag("status", outcome)     // completed | rejected | pending
    .register(meterRegistry)
    .record(duration)

// Gauge — current state (outbox, balances)
Gauge.builder("openbank.outbox.pending_events") { outboxRepo.countPending() }
    .tag("service", serviceName)
    .register(meterRegistry)
```

Required domain metrics per service:

| Service | Metric | Labels |
|---------|--------|--------|
| sepa-payment, domestic-payment, sepa-instant, fx | `openbank.payments.submitted`, `openbank.payments.completed`, `openbank.payments.rejected`, `openbank.payment.processing.duration` | type, currency, status |
| account-service | `openbank.accounts.created`, `openbank.accounts.active` | product_type, currency |
| party-service | `openbank.parties.created`, `openbank.parties.verified` | type (individual\|business) |
| kyc-service | `openbank.kyc.submissions`, `openbank.kyc.verdicts` | outcome (approved\|rejected\|manual_review) |
| sca-service | `openbank.sca.challenges`, `openbank.sca.completions` | method (push\|totp\|biometric), outcome |
| ledger-service | `openbank.ledger.postings`, `openbank.ledger.posting.amount` | debit_credit, currency |
| balance-service | `openbank.balances.revaluations` | currency |
| aml-service | `openbank.aml.screenings`, `openbank.aml.hits` | severity |
| sanctions-service | `openbank.sanctions.screenings`, `openbank.sanctions.hits` | list_type |
| All services | `openbank.outbox.pending_events`, `openbank.outbox.processing_lag_seconds` | — |

Implementation: single `ObservabilityProducer` CDI bean in `openbank-libs`, injected where needed.
Services opt in via `@RegisterMetrics` on their aggregate roots (one annotation, zero boilerplate).

### Pillar 2 — Distributed Traces

**Current state:** Tempo running, `QUARKUS_OTEL_SDK_DISABLED=true` on all services.

**Decision:** Enable OpenTelemetry SDK. Quarkus ships `quarkus-opentelemetry` which provides
zero-config auto-instrumentation for:
- All JAX-RS HTTP endpoints (inbound spans)
- All REST Client calls (outbound spans)
- Kafka producer/consumer (span propagation via headers)
- JDBC / Hibernate (DB query spans)

Configuration (via `openbank-libs` shared `application.properties`):

```properties
quarkus.otel.enabled=true
quarkus.otel.exporter.otlp.endpoint=http://tempo.observability.svc:4317
quarkus.otel.exporter.otlp.protocol=grpc
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=0.1   # 10% in sandbox, 1% in prod
# Propagate W3C TraceContext headers across all service calls
quarkus.otel.propagators=tracecontext,baggage
```

This single change (removing `QUARKUS_OTEL_SDK_DISABLED=true` + adding endpoint config)
gives end-to-end trace coverage for every payment flow with zero service-level code changes.

**Trace-based alerting rules** (to add once traces flow):
- Alert if P99 end-to-end SEPA payment trace > 60 s
- Alert if SCT Inst payment trace > 8 s (2 s buffer before 10 s SLA breach)
- Alert if any payment trace has > 2 Kafka hops (indicates retry storm)

### Pillar 3 — Structured Logs

**Current state:** Promtail collects raw stdout from all pods. Logs are unstructured text.

**Decision:** Enforce structured JSON logging with mandatory correlation fields.

All services already use SLF4J + Logback/JBoss Logging via Quarkus. Add to shared config:

```xml
<!-- openbank-libs/src/main/resources/logback.xml -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <customFields>{"service":"${quarkus.application.name}","version":"${quarkus.application.version}"}</customFields>
    <fieldNames>
      <timestamp>ts</timestamp>
      <message>msg</message>
      <levelValue>[ignore]</levelValue>
    </fieldNames>
  </encoder>
</appender>
```

Mandatory MDC fields (injected by `openbank-libs` request filters):

| Field | Source | Example |
|-------|--------|---------|
| `traceId` | OpenTelemetry MDC bridge | `4bf92f3577b34da6` |
| `spanId` | OpenTelemetry MDC bridge | `00f067aa0ba902b7` |
| `correlationId` | X-Correlation-ID header | `pay-2026-06-10-abc123` |
| `service` | `quarkus.application.name` | `openbank-sepa-payment` |
| `apiVersion` | URL path prefix | `v1` |

With structured logs, Loki queries become:
```logql
{namespace="payments"} | json | status="ERROR" | amount > 10000
{namespace="payments"} | json | traceId="4bf92f3577b34da6"
```

### Pillar 4 — Alerting (enabler for all three pillars)

Alertmanager is currently disabled. Re-enable with routing to a single webhook (Slack / PagerDuty).

**Tier 1 — Always-on (sandbox + prod):**
- Service `up == 0` for > 2 min
- Error rate > 10% for > 5 min on any payment service
- P99 latency > 2 s for > 10 min on any payment endpoint
- Outbox `pending_events > 100` for > 15 min (stuck payment signal)

**Tier 2 — Production-only (add when go-live conditions per ADR-0027 are met):**
- SCT Inst P99 > 8 s (SLA breach imminent)
- SEPA payment not cleared within T+1 business day
- AML hit rate > 5× baseline (potential attack signal)
- Ledger posting imbalance (debit ≠ credit in 15-min window)

---

## Phases

### Phase 1 — Enable traces (1 PR, ~1 day)
Remove `QUARKUS_OTEL_SDK_DISABLED=true`, add OTLP endpoint config to `openbank-libs`.
All services get end-to-end traces automatically.
**Deliverable:** Payment flow traces visible in Grafana/Tempo.

### Phase 2 — Business metrics foundation (1 PR per service cluster, ~3 days)
Add `ObservabilityProducer` to `openbank-libs`. Apply to payment services first
(sepa-payment, domestic-payment, sepa-instant, fx) — highest business value.
**Deliverable:** "OpenBank — Payment Operations" dashboard shows real payment counters.

### Phase 3 — Structured logging (1 PR to openbank-libs, ~1 day)
Add JSON Logback encoder + mandatory MDC fields to shared config.
Correlate logs ↔ traces via `traceId`.
**Deliverable:** Loki queries by `paymentId`, `traceId`, `correlationId`.

### Phase 4 — Alerting (1 PR, ~1 day)
Enable Alertmanager, add Tier-1 alert rules as PrometheusRule CRDs.
**Deliverable:** PagerDuty/Slack alert when service down or payment stuck.

---

## Consequences

### Positive
- End-to-end payment trace from API ingress to Kafka to DB — debuggable in minutes not hours
- Regulatory-grade evidence: SLO compliance reports from Grafana exportable as PDF
- `openbank.outbox.pending_events` as the single most important operational gauge — stuck money surfaced in real-time
- Traces replace most ad-hoc log grepping for production incidents
- Phase 1 is zero service-code changes — immediate wins from one config change

### Negative / Trade-offs
- **Phase 1 (traces):** 10% sampling adds ~2% CPU overhead per service at sandbox load. Acceptable.
- **Phase 2 (domain metrics):** Requires touching every service. Use fleet sweep pattern (ADR-0052 issue + one PR per service cluster). ~7 PRs total.
- **Phase 3 (structured logs):** Logback JSON encoder breaks human-readable local dev logs. Fix with `%dev` profile override in local `application.properties`.
- **Cardinality risk:** `amount` must NEVER be a Prometheus label (infinite cardinality). Amounts belong in histograms (sum/count) or Loki, not as label values.

### Not in scope
- Real-time fraud scoring (→ separate ADR, requires ML pipeline)
- Customer-facing analytics (→ separate data warehouse, not operational observability)
- Log-based billing / reconciliation (→ accounting domain, not Prometheus)

---

## Alternatives Considered

**A. External APM (Datadog / New Relic)**
Rejected: cost ($15/host/month × 15 nodes = $225/month), data residency concerns for banking,
and Tempo + Prometheus already deployed. OSS stack is sufficient.

**B. eBPF-based auto-instrumentation (Cilium / Pixie)**
Rejected for now: requires privileged DaemonSet, incompatible with Karpenter spot node churn.
Revisit for production when node fleet is more stable.

**C. Logs-only approach (no traces)**
Rejected: cross-service correlation via log grep is O(n×services) work per incident.
Traces reduce MTTR by ~10× for multi-service payment flows.

---

## Go-live gate (ADR-0027 condition)

Before production launch, the following must be in place:
- [ ] Phase 1-3 complete (traces, domain metrics, structured logs)
- [ ] Tier-1 alerts active and tested (runbook linked in each alert)
- [ ] 30-day SLO baseline established for each payment rail
- [ ] Outbox lag alert proven in chaos test (kill ledger-service, confirm alert fires < 15 min)
