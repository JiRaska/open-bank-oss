---
date: 2026-06-13
decision-status: accepted
delivery-status: partial
authors: [@JiRaska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, mobile-app, resilience]
summary: "Observability is extended with GoAlert plus ntfy for on-call, Pyrra for SLO-as-code, S3-backed durable retention for Loki and Tempo, and OpenTelemetry mobile RUM behind a hardened public OTLP ingest gateway."
---

# ADR-0088 — Observability Extension: On-Call, SLO-as-Code, Durable Retention & Mobile RUM

**Relates to:** ADR-0008 (OpenTelemetry), ADR-0077 (Three-Pillar Observability), ADR-0087 (Correlation & Profiling Layer), ADR-0075 (Mobile Crash Monitoring), ADR-0056 (Grafana internal-only), ADR-0027 (Cloud Substrate, FinOps), ADR-0054 (FinOps lifecycle), ADR-0061 (DORA metrics)

> **Implementation summary 2026-06-25 — D1/D2/D3 confirmed live; D4 split-tracked.**
>
> - **Step 0 / D1 — GoAlert + ntfy** live: `gitops/components/observability/goalert.yaml` + `ntfy.yaml`.
>   GoAlert-DB has CNPG S3 backup from day one (IRSA, `openbank-cnpg-backup` role). Alertmanager
>   route points at GoAlert generic-API webhook; ntfy is the contact method for paging.
> - **D2 — Pyrra** live: `gitops/components/observability/pyrra.yaml` + `pyrra-crd.yaml`. SLO
>   operator reconciles `ServiceLevelObjective` CRs into multi-window burn-rate `PrometheusRules`.
> - **D2 rollout (2026-07-10, issue #669 scope item 3)** — the sample fleet-wide SLO is now
>   joined by a per-money-path-service pair (availability + latency) for all 17 services in
>   `rules.yaml: money_path_services`: `gitops/components/observability/pyrra-slo-money-path.yaml`,
>   targets governed by `rules.yaml: slo` (99.9% availability / 30d, 99% of requests <1s / 30d;
>   REFERENCE defaults, not calibrated against real traffic — see the file header). CI
>   (`check-slo-registry.py`) fails on drift between the two. `tempo.yaml`'s span-metrics
>   processor gained an explicit `histogram_buckets` list so the latency indicator's `le="1"`
>   selector reads a real bucket (Tempo's default buckets have no exact 1s boundary). This
>   satisfies the go-live gate's "Pyrra SLOs declared ... 30-day error-budget baseline
>   established" item below for the money-path fleet, not just the payment rails.
> - **D3 — Loki + Tempo on S3**: both apps reference `ADR-0088 D3` in their gitops YAML; Loki
>   `object_store: s3` and Tempo `backend: s3` are active in the sandbox (IRSA, `openbank-observability-s3`).
> - **D4a — `opentelemetry-android`** skeleton merged (openbank-app #55); iOS no-op stub in place.
> - **D4b — hardened public OTLP ingest gateway** (`gitops/apps/rum-gateway.yaml` present; threat-model
>   per ADR-0030 in progress). Tracked separately; ADR is **Accepted** because the architectural
>   decisions are stable and D1–D3 are fully deployed. D4b is an implementation follow-up, not a
>   new decision point.
>
> All go-live gate items except the mobile OTLP gateway threat-model sign-off are met or on a
> tracked path with no change to the architectural direction.

---

## Context

ADR-0077 stood up the three pillars; ADR-0087 wired them together and added profiling. The LGTM(P)
stack is live: Loki 3.6.7, Tempo (+ span-metrics / service-graphs), Prometheus (kube-prometheus-stack),
Grafana 13, Alloy, Pyroscope, exemplars, and the correlation web. Mobile crash/error capture lands in
self-hosted GlitchTip (ADR-0075).

A **2026-06-13 audit against the live cluster** found the *signals* are healthy but the **operational
edges are not**:

| Edge | Reality (verified) | Why it bites a bank |
|------|--------------------|---------------------|
| **Alerting last mile** | Alertmanager runs but `route.receiver: 'null'`; the `alertmanager-slack` secret is `SecretSyncedError` (unseeded). | Tier-1 rules fire **into the void**. ADR-0077's go-live gate "alerts active **and tested**" is unmet. A stuck-payment alert nobody receives is not an alert. |
| **On-call / escalation** | None. No paging, no schedule, no escalation. | DORA Art. 17 incident response needs a human reachable with an escalation path, not a dashboard someone might glance at. |
| **Durable retention** | Loki = **filesystem, 72h**; Tempo = **local, 24h**; both on a single gp3 PVC. | DORA Art. 17 (incident reconstruction) and SLO evidence need traces/logs that outlive a node/PVC and span an audit window. 24–72h on ephemeral storage will not satisfy an auditor. |
| **SLO evidence** | Burn-rate alerts are **hand-written** PromQL in `prometheus-rules-tier1`. | ADR-0077 go-live wants a "30-day SLO baseline per payment rail" with error budgets. Hand-rolled rules don't scale or self-document. |
| **Mobile RUM** | Crash only (GlitchTip). No app-start, ANR, frame jank, or device-side network timing. | The customer experience is the mobile app; crash-free ≠ fast. The app already mints W3C `traceparent` (ADR-0087 §mobile) — the device-side spans are missing to close the tap-to-ledger trace. |

This ADR extends — does not replace — 0077/0087 to close these edges with a **strictly OSS, self-hosted,
VPC-internal** posture (ADR-0027), and to make the AGPL/Apache licence split an explicit governance fact.

---

## Decision

Adopt four extensions; explicitly reject/defer the rest. Step 0 unblocks everything.

### Step 0 — Wire the alerting last mile (prerequisite, not a new component)

Seed `openbank/alertmanager SLACK_WEBHOOK` (now possible via the OpenBao recovery key, runbook 0005),
mount the `alertmanager-slack` secret, and flip the Alertmanager route off `null`. Until this is done,
**every** downstream alerting decision (including on-call) has no working source. One hour of work.

### D1 — On-call & incident response: **GoAlert** (+ ntfy)

**GoAlert** (Target, **Apache-2.0**) — a single Go binary + Postgres. Alertmanager → GoAlert webhook;
GoAlert owns schedules, escalation policies, ack/close, and dedup.

- **Notification transport = ntfy** (self-hosted, Apache-2.0), **not Twilio**. Twilio is an external
  cloud SMS/voice dependency that breaks the VPC-internal invariant (ADR-0027). ntfy keeps paging
  inside the perimeter; an external SMS/voice gateway can be added later behind the same abstraction
  if regulators require out-of-band reachability.
- Postgres is a new CNPG cluster — it **must** carry a backup from day one (cf. the PG backup gap,
  runbook 0003); on-call state losing its DB is itself an incident.

### D2 — SLO-as-code: **Pyrra**

**Pyrra** (**Apache-2.0**) — declarative SLO CRDs that generate the multi-window burn-rate
`PrometheusRules` **and** an error-budget UI, single-pane in Grafana. Replaces the hand-written
`prometheus-rules-tier1` burn alerts and directly produces the ADR-0077 go-live "30-day SLO baseline
per payment rail". Chosen over **Sloth** (also Apache-2.0) for the built-in UI; Sloth is a pure rule
generator with no budget view.

### D3 — Durable retention: **S3 object storage for Loki & Tempo**

Flip Loki `object_store` and Tempo `backend` from `filesystem`/`local` to **S3** (the prod path both
ADRs already anticipate), with retention policies sized to the audit window (≥ 30d traces, ≥ 90d logs —
final numbers set with compliance). This is **higher value than any new tool**: it makes existing
signals durable and audit-defensible. No new query path — same Grafana, same LGTM.

### D4 — Mobile RUM: **OpenTelemetry mobile**, not Faro

Instrument the KMP app with **`opentelemetry-android`** (androidMain) and **`opentelemetry-swift`**
(iosMain), both **Apache-2.0, CNCF**. Export **OTLP** → the existing Alloy / OTel Collector → Tempo
(spans) + Prometheus (RED via span-metrics). This is the right choice precisely because:

- The app **already** propagates W3C `traceparent` (ADR-0087 §mobile, `TraceparentPlugin`). Device-side
  OTel spans make the mobile span and the backend span share **one trace id** → a single distributed
  trace **from the user's tap to the ledger write**. Faro (web/JS only) cannot do this and does not
  target mobile at all.
- It reuses the OTLP pipeline already deployed — no new query backend.
- Captures genuine mobile-RUM signal: cold/warm start, ANR, slow/frozen frames, screen views, and
  device-side request timing. Crash stays in GlitchTip (ADR-0075); RUM is the performance layer on top.

**The load-bearing new work is NOT the SDK — it is a hardened public OTLP ingest gateway** (see
§Mobile RUM architecture). The SDK is small; the gateway, PII scrubbing, and consent are the real,
bank-grade effort.

iOS parity caveat (mirrors ADR-0075): `opentelemetry-swift` links via SPM, which this xcodegen build
does not yet vendor. The iOS `actual` ships as a **no-op stub** until the framework is vendored — same
pattern, and same follow-up class, as the Sentry-Cocoa gap. Android has full RUM from day one.

### Explicitly rejected / deferred

| Option | Verdict | Reason |
|--------|---------|--------|
| **Mimir** (AGPLv3) | **Deferred** | Prometheus suffices at sandbox scale. Mimir = S3 + ~6 microservice components = large operational weight on a cluster with active Karpenter churn and a still-maturing backup story. Revisit on retention/scale/HA need; the cheaper intermediate is longer Prometheus retention or a Thanos sidecar. |
| **Grafana Beyla** (Apache-2.0) | **Deferred — contradicts ADR-0077** | Beyla **is** eBPF auto-instrumentation = ADR-0077 *Alternative B* and ADR-0087 *Not-in-scope*, both rejected: "privileged DaemonSet, incompatible with Karpenter spot node churn." Churn is still active. Services are already OTel/Micrometer-instrumented + Tempo span-metrics. Re-open only via a new ADR once the node fleet is stable. |
| **Tetragon** (Apache-2.0) | **Rejected — redundant** | **Falco is already deployed** (eBPF runtime security). Two eBPF security agents = duplicate node burden + alert noise. Tetragon's differentiator is kernel-level *enforcement* (blocking) — that is a separate "replace Falco" decision, not an additive one. |
| **Grafana Faro** (Apache-2.0) | **Rejected — wrong surface** | Web/JS RUM on an **internal** operator console (ADR-0056, ~2 users) is near-zero value; the customer surface is mobile, covered by D4 + GlitchTip. |
| **Full self-hosted Sentry** (perf/transactions) | **Rejected** | GlitchTip does not ingest Sentry performance transactions; full Sentry is FSL-licensed (not OSI-OSS) and operationally heavy (Kafka/Clickhouse/Snuba/Relay). |
| **OpenReplay / session replay** | **Rejected — banking privacy** | Screen recording of customer sessions is a GDPR/PII non-starter for a bank. |

---

## Licensing posture (AGPLv3 vs Apache-2.0) — a governance fact, not a footnote

The Grafana-stack components (**Grafana, Loki, Tempo, Pyroscope**, and **Mimir** if ever adopted)
relicensed to **AGPLv3** in 2024. Used here **unmodified and internally**, the AGPL network-copyleft
clause is **not triggered** — it activates only when a *modified* version is conveyed to third parties
over a network. OpenBank runs these as upstream images to monitor its own infrastructure, so:

- **No source-disclosure obligation arises.**
- **OpenBank's own Apache-2.0 code is not contaminated** — these are separate runtime deployments, not
  linked/derived works.
- **The load-bearing control is ADR-0056 (Grafana internal-only).** "Internal-only" is exactly what
  keeps the AGPL argument sound: no third-party network service, no conveyance. **If a Grafana panel,
  Loki view, or Tempo trace is ever exposed to a customer or TPP, this posture must be re-assessed.**

All net-new components in this ADR (GoAlert, ntfy, Pyrra, opentelemetry-android/-swift) are
**Apache-2.0** — permissive, no copyleft consideration.

**Action:** record this assessment in the governance register (governance-as-code, ADR-0029) and route
it through legal/compliance as part of the AI & tooling governance review. AGPL is a recurring
auditor/regulator touchpoint; documenting the internal-use justification + the ADR-0056 dependency
pre-empts the finding.

---

## Mobile RUM architecture (D4 detail)

```
KMP app (otel-android / otel-swift)
   │  OTLP/HTTP  (+ app-session auth token)
   ▼
[ Public OTLP ingest gateway ]   ← NEW, hardened; the real work
   • auth: token bound to the app session (reject anonymous)
   • rate-limit + payload-size + schema validation
   • PII redaction processor (drop amounts/IBAN/PII attrs + URL params)
   • sampling (battery/data budget)
   ▼  (in-VPC)
Alloy / OTel Collector → Tempo (spans) + Prometheus (RED via span-metrics) → Grafana
```

Three non-negotiables for a bank (these, not the SDK, are why this is an ADR):

1. **Public ingest = new attack surface.** Unlike backend observability (internal, VPC), the data
   source is customer devices on the open internet. The ingest endpoint is therefore public and **must**
   be hardened: app-session-bound auth, rate-limiting, payload/schema validation, and rejection of
   unknown attributes. Mobile RUM **cannot** be VPC-internal end-to-end; the gateway is the controlled
   boundary.
2. **PII scrubbing server-side.** No amounts, IBANs, PII, or sensitive URL params in span attributes —
   a Collector `redaction`/`attributes` processor enforces it before storage, in addition to client
   discipline. Only the pseudonymous `party_id` (ADR-0069) may identify a session, matching ADR-0075.
3. **Consent.** RUM telemetry is folded into the existing consent framework (no blanket collection);
   off by default (ADR-0070 inv. 2), enabled by an app-side flag + a configured endpoint.

---

## Consequences

### Positive
- Tier-1 alerts reach a human with an escalation path (DORA Art. 17); the 0077 go-live "alerts tested" gate is met.
- SLOs become declarative, self-documenting, audit-exportable (0077 "30-day baseline").
- Traces/logs survive nodes and span the audit window (DORA Art. 17 reconstruction).
- One distributed trace from the customer's tap to the ledger write — the end-to-end picture neither pillar gave alone.
- The whole chain stays OSS, self-hosted, in-VPC; the AGPL posture is documented, not latent.

### Negative / trade-offs
- GoAlert + ntfy add a CNPG Postgres (needs a backup) and a paging surface to operate.
- S3 retention adds object-storage cost (FinOps, ADR-0054) — bounded by retention policy and sampling.
- The mobile ingest gateway is a genuinely new, public, security-sensitive surface; it carries the
  bulk of this ADR's effort and risk and must pass threat-modelling (ADR-0030 if customer-data-adjacent).
- iOS RUM lags Android until `opentelemetry-swift` is vendored (no-op stub meanwhile).

---

## Go-live gate additions (extends ADR-0077)

- [ ] Alertmanager routes to a real receiver; a synthetic Tier-1 alert paged + acked end-to-end via GoAlert.
- [ ] On-call schedule + escalation policy defined; GoAlert Postgres backed up.
- [ ] Loki/Tempo on S3 with retention policy ≥ audit window; restore from object store rehearsed.
- [ ] Pyrra SLOs declared for each payment rail; 30-day error-budget baseline established.
      **Declared** (2026-07-10): all 17 money-path services, availability + latency, governed
      by `rules.yaml: slo` — see the D2 rollout note above. **Baseline still open**: the
      sandbox has no real production traffic, so there is no 30-day burn-rate history to
      report yet (issue #669's load-benchmark scope, deferred).
- [ ] Mobile OTLP ingest gateway threat-modelled; PII redaction + auth + rate-limit verified; consent wired.

---

## Implementation phases

1. **Step 0** — alerting last mile (secret + route flip). *Prereq.*
2. **D1** — GoAlert + ntfy (GitOps), Alertmanager → GoAlert.
3. **D2** — Pyrra; migrate `prometheus-rules-tier1` burn alerts to SLO CRDs.
4. **D3** — Loki/Tempo → S3 + retention (FinOps-sized).
5. **D4a** — `opentelemetry-android` in androidMain (real); iOS no-op stub; reuse `traceparent`.
6. **D4b** — hardened OTLP ingest gateway (threat-model first).
7. **D4c** — `opentelemetry-swift` iOS parity (after SPM vendoring; pairs with the Sentry-Cocoa follow-up).

---

## Alternatives Considered

See **Explicitly rejected / deferred** above (Mimir, Beyla, Tetragon, Faro, full Sentry, session replay).
The overarching alternative — an external APM (Datadog/New Relic) — was already rejected in ADR-0077
(cost, data residency); this ADR keeps the strictly-OSS substrate.

## Compliance impact

- PCI DSS: not applicable - no cardholder data; amounts and IBANs are redacted before storage.
- DORA:    engaged - the ADR cites DORA Art. 17 for both incident response (a reachable human with an escalation path) and incident reconstruction (traces and logs that outlive a node and span the audit window).
- GDPR:    engaged - mobile RUM collects device-side telemetry from customer devices, so the ADR requires server-side PII scrubbing, pseudonymous party_id only, and consent with collection off by default; session replay is rejected outright as a GDPR/PII non-starter. No article cited in this ADR.
- PSD2:    not applicable - no payment-service interface or SCA obligation addressed here.
- CNB:     not applicable - no CNB-specific supervisory requirement identified in this ADR.
