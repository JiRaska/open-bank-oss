# ADR-0098 — Progressive Delivery for Money-Path Services via Argo Rollouts

**Status:** Accepted
**Delivery-Status:** Shipped
**Date:** 2026-06-19
**Author:** @JiRaska

**Relates to:** ADR-0029 (Governance as code), ADR-0030 (Threat model gate),
ADR-0031 (AI-agent governance), ADR-0061 (DORA metrics), ADR-0077 (Three-Pillar Observability),
`openbank-libs/governance/rules.yaml: money_path_services`

## Context

OpenBank operates 30 microservices deployed via ArgoCD GitOps. Every service currently uses
Kubernetes `Deployment` with default rolling-update semantics: a new image is rolled out across
all pods, replacing the previous version without any traffic analysis gate. When the rollout
completes the new version is immediately 100% live. The only rollback is a new deploy — triggered
manually, after humans have detected and confirmed the regression.

This is acceptable for infrastructure tooling and stateless adapters but it is not acceptable for
the money-path. Services such as `ledger`, `balance`, `transaction`, `account`, `sepa-payment`,
`sepa-instant`, `domestic-payment`, `clearing`, `swift`, `fx`, `lending`, `sca`, and `consent`
process real monetary operations and handle regulated customer data. A single bad deploy —
misconfigured interest rounding, a regressed idempotency guard, a broken clearing leg — goes
100% live with no automated gate and no automatic rollback. Detection lag is measured in minutes to
hours; during that window every in-flight transaction is at risk.

Three converging pressures make the status quo unacceptable:

1. **DORA metrics gap.** ADR-0061 defines Change Failure Rate (CFR) and Mean Time to Restore
   (MTTR) as primary fleet-health indicators. Today CFR is proxied from 5xx error rates, which
   are a lagging signal measured after a deploy is fully live. We have no deployment-granular
   failure event — only a regression that surfaces in metrics some minutes after the fact. True
   progressive delivery generates a first-class deployment outcome (pass/abort) per release,
   giving CFR a direct source and MTTR a defined start time.

2. **Money-path blast radius.** The thirteen money-path services together account for the
   overwhelming majority of regulatory exposure and customer impact. A weighted canary strategy
   — sending a small fraction of production traffic to the new version, analysing it against
   live Prometheus metrics, and rolling back automatically on failure — bounds the blast radius
   to the canary slice for the duration of the analysis window.

3. **Regulatory change-management expectations.** CNB Vyhl. č. 163/2014 §24 and DORA Art. 12
   both require that production changes pass an automated pre-production gate. A canary
   AnalysisTemplate running against the live service SLIs is the most defensible form of that
   gate: it uses production traffic, production infrastructure, and production error budgets.

The fleet currently has one HPA and 41 PodDisruptionBudgets. No service mesh (Istio, Linkerd) is
deployed. We operate an in-cluster Prometheus stack (ADR-0077) whose per-service HTTP metrics are
already instrumented across the full fleet from the DomainMetrics sweep (tracking #1022).

**Argo Rollouts** (CNCF, Apache-2.0) integrates natively with ArgoCD and Prometheus. It
introduces a `Rollout` CRD that is a drop-in replacement for `Deployment`, manages canary and
blue/green strategies, and drives traffic by keeping two real Kubernetes `Service` objects — a
stable Service and a canary Service — and adjusting pod selectors. No service mesh is required.
`AnalysisTemplate` CRDs query Prometheus on a configurable interval; failure triggers automatic
rollback to the stable version. ArgoCD ships a Rollouts integration plugin that surfaces canary
status in the ArgoCD UI.

## Decision

We will adopt **Argo Rollouts** as the progressive delivery controller for OpenBank money-path
services, deployed via GitOps and integrated with the existing ArgoCD + Prometheus stack. The
rollout is phased across three stages.

### Strategy

**Money-path services** (as defined in `rules.yaml: money_path_services`) will be migrated from
`Deployment` to `Rollout` with a canary strategy:

- Step 1 — set canary weight to **10%**, pause for **5 minutes**.
- Step 2 — set canary weight to **30%**, pause for **5 minutes**.
- Step 3 — promote to **100%** only if both AnalysisTemplate checks pass throughout.

Automatic rollback to the previous stable version is triggered by any AnalysisTemplate failure
during the canary window. ArgoCD automated sync continues to apply the new `Rollout` manifest;
the Rollouts controller governs what fraction of traffic that manifest version actually receives.

Traffic splitting is achieved via two Kubernetes `Service` objects per money-path service — one
`stable` and one `canary` — managed by the Rollouts controller. No service mesh is required;
weighted traffic is achieved through pod-selector manipulation on the two Services.

**Non-money-path services** retain standard `Deployment` + rolling update. The operational
overhead of `Rollout` CRDs, AnalysisTemplates, and dual-service wiring is only justified where
the blast radius is unacceptable. Non-money-path services already have adequate coverage via
the standard Kubernetes readiness probe gate.

### AnalysisTemplate

Each money-path service will reference a shared `AnalysisTemplate` (with optional per-service
overrides) that executes two Prometheus queries over the canary pods' metrics labels:

- **Error rate:** `http_server_requests_seconds_count` with `status=~"5.."` as a fraction of
  total requests — must remain below **1%** for the analysis interval.
- **Latency p95:** `http_server_requests_seconds_duration_seconds_bucket` p95 — must remain
  below **500 ms**.

Both checks run on a **30-second interval**. A single failing interval triggers a rollback; the
threshold is crossed only when the metric fails at the canary weight, not the stable weight,
so the analysis is isolated to the new version's traffic share.

### DORA integration

The Argo Rollouts controller emits deployment outcome events (promoted / aborted). A Prometheus
recording rule will translate these events into the CFR and MTTR source data that ADR-0061 defines.
A deployment abort event becomes a direct change-failure record rather than a post-hoc inferred
regression.

### Phased rollout

**Phase 1 — Controller and Dashboard (prerequisite)**

Install the Argo Rollouts controller and CRDs via an ArgoCD `Application` pointing at the
official Helm chart. Expose the Rollouts dashboard via the existing ArgoCD ingress at an
internal-only path (consistent with ADR-0056 internal-only exposure policy). No service is
migrated in this phase; the objective is a stable controller, validated ArgoCD plugin wiring,
and an operational dashboard before any money-path service is touched.

Deliverables: `openbank-infra/gitops/apps/argo-rollouts.yaml` (Helm Application), ArgoCD plugin
configured, dashboard reachable on internal ingress, AnalysisTemplate CRDs confirmed installed.

**Phase 2 — Pilot: three money-path services**

Migrate `ledger`, `transaction`, and `account` from `Deployment` to `Rollout`. These three
services were chosen as the pilot because they collectively exercise the full canary mechanism:
`ledger` is the most critical single point of monetary correctness; `transaction` has the highest
request volume (the most signal-rich canary); `account` exercises the idempotency paths common
to all money-path services. All three have existing PodDisruptionBudgets and HPA entries;
compatibility will be verified before migration.

A deploy of any pilot service during Phase 2 triggers the canary strategy under real production
traffic. Phase 2 is considered successful when at least two independent deploys of each pilot
service complete the canary analysis and promote cleanly, and at least one synthetic abort test
confirms automatic rollback fires and the stable version is restored within the analysis window.

**Phase 3 — Full money-path fleet rollout**

Migrate the remaining ten money-path services (`balance`, `sepa-payment`, `sepa-instant`,
`domestic-payment`, `clearing`, `swift`, `fx`, `lending`, `sca`, `consent`) from `Deployment`
to `Rollout` using the same canary template validated in Phase 2. Each service migration is a
separate PR to allow independent review and independent abort.

Phase 3 completion is the gate for the DORA CFR/MTTR improvement milestone (ADR-0061).

### Governance notes

- The Argo Rollouts controller runs with a dedicated ServiceAccount scoped to the minimum RBAC
  required to manage `Rollout`, `AnalysisRun`, and Service objects. It does not require cluster-admin.
- ArgoCD `--sync-policy automated` is preserved. ArgoCD continues to apply manifests; Rollouts
  governs traffic promotion within those manifests.
- The Rollouts dashboard is internal-only (ADR-0056). No external ingress path is opened.
- Money-path service threat models (ADR-0030) will each be amended in their respective Phase 3
  migration PRs to note the progressive delivery boundary as a deployment control.

## Alternatives considered

**Flagger (WeaveWorks, Apache-2.0)** — Flagger is the other widely-adopted CNCF progressive
delivery controller and is architecturally similar to Argo Rollouts. However, Flagger's
traffic-splitting mechanism for non-mesh environments relies on ingress controller annotations
(e.g. nginx weight annotations) rather than native Service-level pod-selector manipulation.
This introduces a dependency on NGINX Ingress internals for what is fundamentally a pod-level
concern. More significantly, Flagger's full traffic analysis capability requires a service mesh
(Linkerd or Istio) for precise per-request weight enforcement. Running Linkerd or Istio adds
mTLS sidecar injection, control-plane overhead, and a new failure domain to the fleet — unjustified
at the current scale. Rejected.

**Manual canary via feature flags (ADR-0067 / flagd)** — Feature flags (ADR-0067) are designed
for application-level behaviour changes: a feature is enabled or disabled per user segment or
environment. They operate inside the application boundary. Using flagd to implement a deployment
canary would require the application to be flag-aware at the traffic-routing level, which bleeds
deployment control into business logic. It also creates a second control plane for the same
problem: both flagd and the GitOps pipeline would need to agree on which version of an
application is the canary. Rejected: flags and progressive delivery solve different problems
at different levels.

**Service mesh (Istio + VirtualService)** — Istio provides precise per-request traffic splitting
via `VirtualService` and `DestinationRule`, which when combined with Flagger or Rollouts gives
the most accurate canary analysis. It also adds mTLS, detailed L7 telemetry, and circuit-breaking
as a byproduct. However, the operational weight of an Istio control plane — sidecar injection at
fleet scale, Envoy proxy overhead per pod, istiod as a new critical-path dependency, a non-trivial
upgrade cadence — is disproportionate to the problem we are solving. The Argo Rollouts native
Service-level approach is sufficient for our traffic volumes and our Prometheus-based analysis.
Deferred: revisit if the fleet grows to a scale where per-request routing precision becomes
necessary or if Linkerd/Istio is adopted for other reasons (e.g. zero-trust mTLS, ADR-0034).

**Status quo (Kubernetes rolling update)** — Standard rolling update replaces pods one at a time
governed only by readiness probes. There is no traffic-weighted canary, no automated metric
analysis, no automatic rollback, and no deployment-granular failure event for DORA. CFR remains
a retrospective inference from 5xx rates. For non-money-path services this is an acceptable
trade-off; for money-path services the blast radius — a regression going 100% live with no gate
— is not acceptable given the regulatory environment and customer impact. Rejected for money-path.

## Consequences

**Positive**
- Money-path deploys go through an automated Prometheus-backed analysis gate before 100% promotion.
  A bad version is automatically rolled back within the canary window, bounding blast radius to
  the canary weight slice.
- DORA CFR and MTTR gain a first-class deployment outcome event source (abort = change failure
  with a defined start time), replacing the current lagging 5xx proxy.
- The Rollouts UI on the internal ArgoCD ingress gives operators live canary progress visibility
  without requiring kubectl access.
- No service mesh required: the native Kubernetes dual-Service approach is sufficient and keeps
  the infrastructure surface minimal.
- Argo Rollouts is Apache-2.0, CNCF-incubating, and has a native ArgoCD integration — consistent
  with the OSS substrate commitment (ADR-0027).

**Negative**
- Each money-path service grows from one `Deployment` manifest to a `Rollout` manifest plus two
  `Service` manifests (stable + canary). Migration PRs are mechanical but numerous.
- `AnalysisRun` objects accumulate in the cluster. A retention policy (TTL or a periodic cleanup
  job) is needed to prevent unbounded growth.
- A canary with 10% weight requires sufficient baseline traffic for the AnalysisTemplate Prometheus
  queries to have statistical meaning. In low-traffic environments (sandbox off-hours) the canary
  analysis window may see too few requests to produce a reliable signal; a minimum-request guard or
  an extended analysis interval may be needed per environment.
- The Rollouts controller is a new cluster-level dependency. Controller downtime would leave
  in-progress `Rollout` objects paused. A controller outage must be treated with the same priority
  as an ArgoCD controller outage.
- Existing HPA objects attach to `Deployment`. After migration to `Rollout`, the HPA target
  reference must be updated to the `Rollout` kind. This is a required step in each Phase 3 PR.

**Neutral**
- Non-money-path services are unaffected. No operational change to their deploy pipeline.
- ArgoCD automated sync semantics are unchanged; the Rollouts controller operates below the
  ArgoCD reconciliation layer.
- Existing PodDisruptionBudgets remain valid; Rollouts respects PDB constraints during canary
  promotion.

## Compliance impact

- **DORA (EU regulation 2022/2554):**
  Art. 12 — change management: canary AnalysisTemplate serves as the automated testing gate
  before full production rollout, satisfying the requirement for documented, automated pre-production
  controls. Art. 17 — incident response and recovery: automated rollback reduces MTTR and produces
  an auditable deployment failure event; the rollback window is bounded by the canary analysis
  interval rather than by human detection time.
- **PCI DSS:** not directly applicable; progressive delivery reduces the blast radius of a
  compromised or malformed build reaching 100% of cardholder-data-touching pods.
- **GDPR:** not applicable.
- **PSD2:** not applicable.
- **CNB:** Vyhl. č. 163/2014 §24 — change and release management; canary analysis under live
  production traffic constitutes the automated pre-production gate the regulation requires for
  systems that process payment orders and account operations.

## References

- Argo Rollouts documentation: https://argoproj.github.io/argo-rollouts/
- ADR-0029 (Governance as code / CI gates)
- ADR-0030 (Threat model gate for money-path services)
- ADR-0031 (AI-agent governance)
- ADR-0061 (DORA metrics: CFR and MTTR)
- ADR-0077 (Three-Pillar Observability — Prometheus stack)
- `openbank-libs/governance/rules.yaml: money_path_services` — authoritative money-path service list
- DORA EU Regulation 2022/2554, Art. 12 (change management) and Art. 17 (incident response)
- CNB Vyhl. č. 163/2014 §24 (change and release management requirements)
