---
date: 2026-06-09
decision-status: accepted
delivery-status: partial
followup: "openbank-app — the client-side Sentry KMP SDK wiring is tracked in the app repo, as this ADR's own text states"
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [mobile-app, observability]
summary: "The customer app gets production crash and error monitoring via a self-hosted in-cluster GlitchTip through the Sentry KMP SDK, off by default with a PII redaction allowlist, keeping data EU-resident and vendor-free."
---

# Mobile app crash & error monitoring (customer app)

> **Migrated status note.** The pre-schema `Status:` line carried this prose,
> which the enum cannot hold; it is kept here rather than dropped:
> 2026-06-14 — backend/infra implemented: GlitchTip deployed via GitOps (`openbank-infra/gitops/apps/glitchtip.yaml`, external-secrets wired) and live; the Sentry KMP SDK (off-by-default, redaction allowlist) landed in the openbank-app repo. Client-side wiring beyond the shared CrashMonitor seam is tracked in the app repo.

**Delivery note (updated 2026-06-30):**
- **Backend/infra** — ✅ Shipped: GlitchTip deployed via GitOps (`openbank-infra/gitops/apps/glitchtip.yaml`), ExternalSecrets wired, Sentry KMP SDK landed in `openbank-app` with off-by-default gate + PII redaction allowlist.
- **Client-side wiring** — Partial: full client-side wiring beyond `CrashMonitor` seam and app-of-apps helm scoping ongoing in app repo; release-health versioning axis pending.

## Context

The backend has a complete observability posture (ADR-0008): every service emits
OpenTelemetry traces, metrics and logs into an in-cluster LGTM stack — OTel
Collector → Tempo (traces), Loki (logs), Prometheus/Grafana (metrics), codified
in GitOps (ADR-0027). Cross-service incidents are reconstructed from the
`X-Correlation-ID` header (`openbank-libs/web/CorrelationIdFilter`), which the
in-app diagnostics surface (ADR-0070) already exposes on-device.

The KMP customer app (ADR-0064) is acquiring real flows — onboarding (ADR-0069),
passwordless auth (ADR-0066), SCA device approval (ADR-0021) — yet it has **no
production crash or error monitoring at all**. ADR-0064 states telemetry
*"inherits the backend posture"*, but that only covers failures that **reach the
backend**. A whole class of client-side failures is structurally invisible to
Tempo/Loki because no request is ever made, or the process dies before one
completes:

- native crashes (Kotlin/Swift exceptions, Compose render exceptions, OOM),
  **ANRs**, and crash-on-launch;
- failures *before* the API call — TLS / certificate-pinning rejection, offline,
  deserialization, SCA / Keystore / Secure-Enclave errors;
- per-release client regressions — there is no **release-health** signal tying a
  spike of crashes to a specific app version (`version.txt` axis).

ADR-0070 does **not** close this gap: it is a *debug-build-only*, on-device read
surface (compiled out of release binaries). It answers "what is happening on
*this* device right now" for a tester holding the phone; it is explicitly **not**
a production, off-device aggregation of what is failing across the fleet. We need
the production complement.

The constraint is that any solution must respect the platform concept: OSS,
cloud-agnostic, self-hostable in-cluster, EU data residency, and the same
PII-minimisation discipline as the rest of the estate. Vendor crash SDKs
(Crashlytics, Bugsnag, Sentry SaaS) are out for the same reason ADR-0008 forbids
vendor tracing SDKs — lock-in, US data residency, and an off-platform PII sink.

## Decision

We will add **production crash & error monitoring to the customer app**, backed
by a **self-hosted, in-cluster GlitchTip** instance, governed by the following
invariants.

**1. GlitchTip is the crash sink; it is deployed, not depended-on.** GlitchTip
is a lightweight, Sentry-wire-compatible error tracker (Django + PostgreSQL +
Redis) — orders of magnitude lighter than self-hosted Sentry (which needs Kafka,
ClickHouse, Snuba, Relay) and a true OSS license. It runs in the `observability`
namespace via GitOps (`openbank-infra/gitops/apps/glitchtip.yaml`), exactly like
Tempo/Loki, with EU-only in-cluster storage — satisfying data residency by
construction.

The licensing question is settled by precedent, not exception: **GlitchTip is
AGPL-3.0, and so are Loki, Tempo and Grafana**, which already run in this same
cluster. The `rules.yaml` license **denylist (GPL/AGPL) governs code
dependencies in the SBOM** — what we *link and ship* — not network-isolated
infrastructure services we *deploy and talk to over the wire*. GlitchTip is the
latter. The thing that actually ships inside the app binary is the **client
SDK**, which is **MIT** (see invariant 2) — no copyleft enters the artifact.

**2. The app uses the Sentry Kotlin Multiplatform SDK (MIT), off by default.**
`sentry-kotlin-multiplatform` is MIT-licensed and speaks the same wire protocol
GlitchTip ingests, with one `expect`/`actual` init shared across Android and
iOS. It is wired but **disabled by default** and gated behind a `libs/flags`
feature flag (ADR-0067), mirroring the rollout discipline used for push
notifications — no telemetry leaves a device until the flag is on for that build.
The DSN points at the in-cluster GlitchTip ingest endpoint, never at
`sentry.io`.

**3. A hard PII redaction gate reusing the ADR-0070 allowlist.** A crash report
is an export, so it is bound by the **same redaction allowlist as the debug
surface** (ADR-0070 invariant 3). A `beforeSend` hook strips everything on that
list before any event leaves the device — **never** access/refresh/ID tokens,
SCA private keys, OTP secrets, signed assertions, SCA `DynamicLinkingData`
(amount/payee), raw request/response bodies with PII, or pre-sanitisation audit
payloads. Permitted: the pseudonymous `party_id` (already a JWT claim,
ADR-0069), `X-Correlation-ID` / `X-Request-ID`, app version, OS, device model,
and breadcrumbs that are themselves redaction-clean. The client IP is **not**
stored (`SENTRY_USE_X_FORWARDED_FOR`/PII off at the server; scrubbed at ingest).
Event sampling is applied to bound volume and FinOps cost (ADR-0027).

**4. Correlation over duplication — it complements OTel, it is not a second
tracing stack.** GlitchTip captures **client-side crashes/errors only**; it does
**not** become a parallel APM or replace the LGTM backend. Every event is tagged
with the `X-Correlation-ID` of the in-flight (or last) request and the
`party_id`, so a mobile crash links straight to its backend trace in Tempo —
client failure and server trace reconciled by the same id the rest of the estate
already uses (ADR-0070 invariant 4).

**5. Symbolication is release-bound and CI-driven.** Android R8/ProGuard mapping
files and iOS dSYMs are uploaded from CI **keyed to the app version**, so stack
traces are symbolicated and **release health is reported per release** (crash-free
sessions by version). This binds crash monitoring to the existing release axis
(`version.txt`, release-please) rather than inventing a new versioning concept.

This is a **customer-app channel** decision. The app lives in a separate
repository (`openbank-app`); this ADR records the architecture and the
server-side (GitOps) half. The client SDK wiring (invariants 2, 3, 5) lands as
follow-up work in `openbank-app`, tracked as a GitHub issue, not in this monorepo.

## Alternatives considered

- **Self-hosted Sentry** — the most capable option and the same client SDK, but
  its server is **BSL 1.1 (Business Source License, not OSS-approved)** and
  operationally heavy (Kafka +
  ClickHouse + Snuba + Relay, ~20 containers) — at odds with the lean,
  cloud-agnostic, OSS-only substrate (ADR-0008/0027). Rejected; GlitchTip gives
  the same wire protocol and the crash DX we need at a fraction of the footprint.
- **Pure OpenTelemetry on mobile (OTel Android/Swift, Apache-2.0) → existing
  LGTM** — the cleanest license/concept fit and zero new infra, but mobile crash
  symbolication, crash *grouping*, and release-health UI are immature / DIY in
  Grafana today. Kept as a **possible future convergence** if OTel's mobile crash
  story matures, but it does not yet deliver the "Sentry-like" DX this gap needs.
  Not chosen now.
- **SaaS crash reporting (Crashlytics / Bugsnag / Sentry SaaS)** — best DX, zero
  ops, but vendor lock-in, US data residency, an off-platform PII sink, and a
  direct contradiction of ADR-0008's "no vendor SDKs". Rejected.
- **Ship nothing; rely on store-console crash reports (Play / App Store)** — no
  symbolication control, no backend correlation, no pre-API failures, and nothing
  in the sandbox/F-stage where there is no store at all. Rejected.

## Consequences

**Positive**
- The structurally-invisible class of client failures (native crashes, ANRs,
  pre-API errors, per-release regressions) becomes visible for the first time.
- Crash events link to backend traces by `X-Correlation-ID`, so support pulls one
  thread from phone to ledger.
- Stays inside the concept: OSS, in-cluster, EU-resident, GitOps, PII-minimised —
  reusing the ADR-0070 redaction allowlist rather than inventing a second one.

**Negative**
- A new stateful service (GlitchTip: Postgres + Redis) to run and back up; budget
  for it with retention + sampling (FinOps, ADR-0027).
- The redaction allowlist becomes a **shared** maintenance obligation across the
  debug surface (ADR-0070) and the crash `beforeSend` — one allowlist, two call
  sites; a new PII field must be added in both.
- AGPL operational hygiene: GlitchTip must run as an unmodified deployed service;
  any fork/modification would carry AGPL source-offer obligations (we do not fork).

**Neutral**
- Like Tempo/Loki/metrics-server, no root app-of-apps yet syncs
  `gitops/apps/`, so GlitchTip is applied with `kubectl apply` until the
  app-of-apps is wired (same follow-up as the rest of the observability apps).
- The client-side half is out of this repo; this ADR is GitOps + decision only.

## Compliance impact

- PCI DSS: not applicable (no PAN/card data; redaction gate excludes it anyway).
- DORA:    Art. 11 (ICT continuity) — client crash visibility + correlation-id
           linkage materially improves the 24h incident-reconstruction path on
           the channel that currently has a blind spot.
- GDPR:    Art. 5(1)(c) data minimisation, Art. 25 data-protection-by-design,
           Art. 32 security — the `beforeSend` redaction gate (invariant 3) is
           the control; EU-only in-cluster sink (invariant 1) satisfies residency;
           client IP not stored.
- PSD2:    SCA (ADR-0021) secrets and `DynamicLinkingData` explicitly excluded
           from any event (invariant 3).
- CNB:     no direct impact; a self-hosted, EU-resident diagnostics sink is
           consistent with the cloud-agnostic substrate (ADR-0027).

## References

- ADR-0008 — OpenTelemetry for observability (backend LGTM; no vendor SDKs)
- ADR-0021 — SCA decoupled device approval (secrets excluded from events)
- ADR-0027 — Cloud-agnostic in-cluster OSS substrate (EU residency, FinOps)
- ADR-0064 — Customer app: Kotlin Multiplatform (the channel)
- ADR-0066 — Passwordless customer authentication
- ADR-0067 — Feature flags and experimentation (`libs/flags`, off-by-default gate)
- ADR-0069 — Customer onboarding journey (`party_id` claim)
- ADR-0070 — In-app diagnostics / debug surface (shared PII redaction allowlist)
- `openbank-infra/gitops/apps/glitchtip.yaml` — the deployed sink
- `openbank-infra/gitops/apps/{tempo,loki}.yaml` — AGPL-in-cluster precedent
