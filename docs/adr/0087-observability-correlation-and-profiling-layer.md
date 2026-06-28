# ADR-0087 — Observability Correlation & Profiling Layer

**Status:** Accepted
**Delivery-Status:** Partial
**Date:** 2026-06-11
**Implemented:** 2026-06-25
**Author:** @JiRaska
**Relates to:** ADR-0008 (OpenTelemetry), ADR-0077 (Three-Pillar Observability), ADR-0075 (Mobile Crash Monitoring), ADR-0027 (Cloud Substrate / FinOps), ADR-0054 (FinOps)

---

## Context

ADR-0077 stood up the three pillars (metrics, traces, logs) as *separate* signals. A bank does
not debug a stuck payment by looking at three disconnected tools — it needs to pivot from a
symptom to its cause in one click: a latency spike → the slow trace → that trace's logs → the CPU
flame graph → and, for the customer-facing app, the mobile crash that started it.

The 2026-06-11 audit found the pillars were live but **not wired together**, and two stores were
stale/limited:

| Gap | Finding |
|-----|---------|
| Loki version | `loki-stack` chart shipped Loki **2.6.1 — Past-EoL** (no TSDB, no structured metadata, no native OTLP). The admin-UI Control Tower flagged it red. |
| Log shipper | Promtail (bundled in loki-stack) is itself EoL, superseded by Grafana Alloy. |
| trace ↔ logs | One-directional only; logs carried `correlationId` but **not** `trace_id`/`span_id`, so a log line could not link back to its trace. |
| trace ↔ metrics | Tempo `metrics-generator` was **off** — no RED metrics, no service graph from spans. |
| metric → trace | No **exemplars** — a latency histogram point could not jump to the trace behind it. |
| profiling | No 4th pillar — "why is this span slow" had no CPU/alloc answer. |
| mobile | GlitchTip (ADR-0075) lived in isolation, not on the Grafana pane. |
| alerting | Alertmanager disabled; no SLO/error-budget. |

## Decision

Build the **correlation layer** that turns four stores into one navigable pane, keyed on
`trace_id` + `X-Correlation-ID`, and add **continuous profiling** as the 4th pillar. Maximise what
the community LGTM(P) stack gives a bank — fully self-hosted, EU-resident (ADR-0027), no APM vendor.

### 1. Stores — modernise & generate

- **Loki 3.x** via the canonical `loki` chart (TSDB v13, `allow_structured_metadata`). Replaces the
  Past-EoL loki-stack/Loki 2.6.1.
- **Grafana Alloy** DaemonSet replaces EoL Promtail. It tails pod stdout, parses the Quarkus JSON
  log, and lifts `mdc.traceId` / `mdc.spanId` / `mdc.correlationId` into Loki **structured
  metadata** — queryable and linkable without becoming high-cardinality stream labels.
- **Tempo metrics-generator** (span-metrics + service-graphs) remote-writes to Prometheus →
  fleet-wide `traces_spanmetrics_*` (RED) and `traces_service_graph_*` (dependency edges) with
  **zero service instrumentation**.

### 2. Correlation web (Grafana datasources)

Every datasource cross-links to the others, so one click pivots between signals:

| From → To | Mechanism |
|-----------|-----------|
| metric → trace | Prometheus `exemplarTraceIdDestinations` → Tempo (needs `exemplar-storage` + OpenMetrics scrape) |
| trace → logs | Tempo `tracesToLogsV2` on `trace_id` → Loki |
| logs → trace | Loki `derivedFields` on the `trace_id` structured-metadata → Tempo |
| trace → metrics | Tempo `tracesToMetrics` → span-metrics in Prometheus |
| trace → profiles | Tempo `tracesToProfiles` → Pyroscope flame graph |
| service map | Tempo `serviceMap` + `nodeGraph` from service-graph metrics |
| mobile crash → backend | GlitchTip event `X-Correlation-ID` → Loki/Tempo pivot (shared `trace_id` when the app emits W3C performance) |

### 3. Structured logs carry trace context (ADR-0077 Phase 3)

`openbank-libs` shared config keeps the Quarkus **structured** JSON formatter (correct escaping)
and emits OTel `traceId`/`spanId` + `correlationId` under `mdc`. The inert hand-rolled
`console.format` JSON string is removed. Alloy promotes those into Loki structured metadata.

### 4. Profiling — the 4th pillar

**Grafana Pyroscope** single-binary sink + Grafana datasource + `tracesToProfiles`. JVM profile
ingest (Pyroscope Java agent / async-profiler) is **opt-in, off by default** — a bank enables
profiling deliberately, never fleet-wide-on. Pilot on `sepa-payment`, then expand.

### 5. Alerting & SLO (ADR-0077 Phase 4)

Alertmanager enabled (no-op receiver in sandbox; prod wires Slack/PagerDuty via ESO). Tier-1
PrometheusRules (service down, error rate >10%, p99 >2s, **outbox backlog >100**) plus
**multi-window multi-burn** SLO alerts (fast 14.4× / slow 6×) against a 99.9% payment-rail target.
`openbank.outbox.backlog` gauge added to `DomainMetrics` — the single most important "stuck money"
signal; per-service wiring is the follow-up sweep.

### 6. Dashboards (provisioned as code)

`Service Map & RED (from traces)`, `Payment SLO & Error Budget`, `Mobile Crashes & Release Health`
— all ConfigMap-provisioned, driven by generated metrics so they light up without per-service work.

### 7. Documented & visualised in admin-UI

A derived **Observability** explainer page in admin-UI renders the correlation graph + pillar
status so non-engineers understand how a signal flows and links. Control Tower lifecycle data is
refreshed so the Past-EoL Loki/Tempo cards clear.

## Phases

1. **Stores** — Loki 3.x chart, Alloy shipper, Tempo metrics-generator. *(this PR)*
2. **Correlation** — exemplars, full datasource cross-links, structured-log trace_id. *(this PR)*
3. **Dashboards + alerting + SLO** — RED/service-map, SLO, Tier-1 rules, Alertmanager. *(this PR)*
4. **Profiling** — Pyroscope sink + wiring *(this PR)*; JVM agent ingest *(follow-up, off by default)*.
5. **Business metrics sweep** — `DomainMetrics` callsites + outbox backlog per service *(follow-up, 1 PR/service; money-path + threat model)*.
6. **Mobile shared-trace** — app emits W3C `trace_id` so crash links straight to backend trace *(openbank-app follow-up)*.

## Consequences

### Positive
- One-click pivot across all four pillars + mobile crashes — MTTR for multi-service payment flows drops sharply.
- RED metrics, service graph and SLO boards come **free** from span-metrics — no fleet instrumentation.
- Off the Past-EoL Loki; modern, structured-metadata-native log store.
- Continuous profiling answers "why slow" at CPU/alloc granularity, linked from the trace.
- Regulatory-grade SLO/error-budget evidence (PSD2/EBA), exportable.

### Negative / Trade-offs
- Tempo metrics-generator adds CPU/memory to Tempo (sized up modestly) and a Prometheus remote-write series load — bounded by low-cardinality span-metrics dimensions.
- The Loki 3.x + Alloy migration is a store swap; needs a deploy + verify pass (trace→logs round-trip, no datasource-default collision).
- Profiling has ~1–2% CPU overhead per profiled JVM — hence opt-in.
- **Cardinality contract (unchanged):** amounts/IDs never become Prometheus labels or Loki stream labels — amounts → histograms, ids → structured metadata.

### Not in scope
- OTLP-native log export to Tempo/OTel (logs stay Loki-via-Alloy).
- eBPF auto-instrumentation (revisit for prod, ADR-0077 alt B).
- Tail-based sampling (separate follow-up on the OTel Collector).

## Implementation note (2026-06-25)

All Phase 1 GitOps artefacts shipped in `feat/adr0087-observability-correlation`:

- `openbank-infra/gitops/apps/loki.yaml` — Loki 3.x (chart `loki` 6.55.0, app v3.6.7) in
  SingleBinary mode with TSDB v13, S3 durable storage (IRSA), `allow_structured_metadata: true`.
  Replaces the removed Past-EoL `loki-stack` (Loki 2.6.1).
- `openbank-infra/gitops/apps/alloy.yaml` — Grafana Alloy DaemonSet (chart 0.10.1) replaces EoL
  Promtail; parses Quarkus JSON MDC and lifts `trace_id`/`span_id`/`correlationId` into Loki
  structured metadata.
- `openbank-infra/gitops/apps/tempo.yaml` — Tempo metrics-generator enabled with
  `span-metrics`, `service-graphs`, and `local-blocks` processors; remote-writes
  `traces_spanmetrics_*` + `traces_service_graph_*` to Prometheus.

Phases 2–6 (exemplars, profiling agent, business metrics sweep, mobile trace) are tracked in
follow-up issues as noted in the Phases section above.

## Go-live gate (ADR-0027)
- [ ] Loki 3.x + Alloy deployed; trace→logs and logs→trace verified end-to-end.
- [ ] Tempo metrics-generator producing span/service-graph metrics; service map renders.
- [ ] Exemplars visible on a payment latency panel and click through to a trace.
- [ ] Tier-1 alerts active and tested (outbox-backlog alert proven by chaos test).
- [ ] 30-day SLO baseline per payment rail.
- [ ] Control Tower shows no Past-EoL observability components.
