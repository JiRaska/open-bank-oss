---
date: 2026-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability]
summary: "OpenTelemetry is the only instrumentation standard for traces, metrics and logs, with a Collector DaemonSet and a Grafana LGTM reference backend; vendor SDK integrations are forbidden to keep operators vendor-neutral."
---

# 8. OpenTelemetry for observability

## Context

A banking platform is operationally illegible without observability. Customer reports a problem; engineers spend hours grepping logs across 26 services trying to correlate. Outages are detected by customer complaints, not by alerts.

Per-vendor solutions (Datadog, New Relic) work but lock operators into a single vendor and a single billing model. The OpenBank reference platform must remain cloud-agnostic and operator-choice-friendly.

OpenTelemetry has emerged as the CNCF-blessed open standard for traces, metrics, and logs across vendors and clouds.

## Decision

OpenTelemetry is the **only** observability instrumentation standard for OpenBank:

- Every service emits OTel traces, metrics, and logs.
- Quarkus extension `quarkus-opentelemetry` is the default for JVM services.
- TypeScript services use `@opentelemetry/sdk-node`.
- OTel Collector is deployed as a DaemonSet in each cluster; services send signals to the local agent.
- The reference backend stack uses Grafana LGTM (Loki for logs, Grafana for dashboards, Tempo for traces, Mimir or Prometheus for metrics).
- Operators MAY route to any OTel-compatible backend (Datadog, New Relic, SigNoz, Honeycomb).
- Trace context propagated via W3C Trace Context (`traceparent`, `tracestate`).
- Baggage carries: customer pseudonymous ID, request budget remaining, tenant ID.

Direct integrations to vendor SDKs (Datadog DD-trace, New Relic agent) are **forbidden** in this codebase. Operators choosing those vendors do so via OTel Collector exporters.

## Consequences

**Positive**
- Vendor-neutral; operators choose their backend.
- Unified semantic conventions across all services.
- Distributed tracing works across language boundaries (JVM ↔ TypeScript).
- Standard ecosystem of dashboards, alerts, and tooling.

**Negative**
- OTel SDK overhead (CPU, memory) per service; budget ~5% overhead.
- Sampling decisions are tricky; head-based vs tail-based trade-offs.

**Mitigation**
- Sampling: 100% of error traces, 10% of slow traces (> p95), 1% baseline.
- Tail-based sampling at Collector for richer per-trace decisions.

## References

- OpenTelemetry specification
- CNCF graduated project
- W3C Trace Context (TR/trace-context/)
