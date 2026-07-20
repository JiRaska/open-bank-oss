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

## Alternatives considered

- **Per-vendor observability solutions (Datadog, New Relic)** — instrument services directly against a vendor SDK such as DD-trace or the New Relic agent. The ADR accepts that these work, but rejects them because they lock operators into a single vendor and a single billing model, which fails the cloud-agnostic, operator-choice requirement; direct vendor SDK integrations are forbidden in the codebase, and operators who choose those vendors route to them through OTel Collector exporters instead.
- **Status quo: no unified instrumentation** — engineers grep and correlate logs across 26 services by hand and outages surface as customer complaints rather than alerts. Rejected as operationally illegible for a banking platform.

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

## Compliance impact

- PCI DSS: not applicable — no cardholder data in the signals described here.
- DORA:    engaged — this is an ICT monitoring and incident-detection control; specific articles not mapped in this ADR.
- GDPR:    engaged — trace baggage carries a pseudonymous customer ID and tenant ID; specific articles not mapped in this ADR.
- PSD2:    not applicable — instrumentation standard, no payment interface changed.
- CNB:     not applicable — no CNB requirement is referenced in this ADR.

## References

- OpenTelemetry specification
- CNCF graduated project
- W3C Trace Context (TR/trace-context/)
