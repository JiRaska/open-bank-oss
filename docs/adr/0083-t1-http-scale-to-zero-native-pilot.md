# ADR-0083 — T1 (HTTP → 0): native-image + KEDA HTTP add-on pilot on product-catalog

Date: 2026-06-02
Status: Accepted
Author(s): Jiri Raska

> **Renumbered 0059 → 0083 (2026-06-11).** This ADR was originally filed as ADR-0059, a
> number it accidentally shared with the (more widely referenced) outbound-oversight-webhooks
> ADR, which keeps 0059. Historical references to "ADR-0059" in a scale-to-zero / FinOps-tier
> context mean this document.

## Context

ADR-0057 defined four FinOps workload tiers and proved **T2** (event → 0) on
`notification-service`. **T1 (HTTP → 0)** — scaling a stateless request/response
service from and to zero on inbound HTTP — is the **largest remaining gap**: it is the
tier that would reclaim the standing compute of the always-on HTTP fleet, and **nothing
for it exists yet** (no native build, no HTTP scale-from-zero mechanism).

The measured tier classifier (ADR-0057, live) already names the pilot target:

```
product-catalog  -> T1   (undeclared, idle HTTP read service, cpu ~0.004 cores)
```

`product-catalog` is **not** a money-path service (rules.yaml), is a read service, and
sits at near-zero CPU — an ideal first T1. Two enablers are missing:

1. **Cold-start budget.** Today every service ships as a **JVM fast-jar**
   (`eclipse-temurin:25-jre`, Quarkus 3.33.2). A JVM cold start is ~1–2 s — too slow to
   hide behind a request when scaling from zero. A Quarkus **native** image (Mandrel /
   GraalVM) cold-starts in **tens of milliseconds**, which is what makes HTTP → 0 viable
   on the request path rather than only for background jobs.
2. **An HTTP scale-from-zero mechanism.** KEDA 2.19 is installed but its **HTTP add-on**
   (the component that holds an inbound request while the deployment scales 0 → 1) is
   not. KEDA's plain `ScaledObject` (used for T2) reacts to a queue/metric, not to a
   live HTTP request, so it cannot wake a service *on* the first request.

## Decision (proposed)

**Pilot T1 on `product-catalog`: build it as a Quarkus native image, front it with the
KEDA HTTP add-on (`minReplicas: 0`), and gate promotion on a measured cold-start /
p95-latency SLO.** Keep it on the existing Kubernetes + KEDA substrate (ADR-0027
cloud-agnostic intact — no FaaS). Success = a repeatable recipe for the rest of the
read/HTTP fleet; failure = revert to `min>0` with no harm.

KEDA is deployed cluster-wide (ADR-0041 P1 confirmed).
Next step: install KEDA HTTP add-on in the sandbox cluster and create an HTTPScaledObject
for the nominated T1 HTTP pilot service. Implementation tracked separately.

### Enabler 1 — native image build

- Add a **native build path** to `product-catalog` (Mandrel container build:
  `-Dquarkus.native.enabled=true -Dquarkus.native.container-build=true`), producing a
  `quarkus-micro-image`/`ubi-minimal`-based native image.
- A **separate CI lane** builds the native image (it must not slow the JVM PR gate).
- Register any runtime reflection/resources Quarkus can't infer (most is automatic at
  3.33.2; verify with the native integration test).

### Enabler 2 — KEDA HTTP add-on (recommended over Knative)

- Install the **KEDA HTTP add-on** via OpenTofu in the platform env (same way KEDA/ARC
  landed), behind a feature flag. Define an `HTTPScaledObject` for `product-catalog`
  with `minReplicas: 0`; route its ingress through the add-on **interceptor**, which
  parks the first request and scales 0 → 1.
- **Why not Knative Serving:** Knative is more capable (revisions, traffic-splitting,
  request-based autoscaling) but heavier — its own control plane (activator, autoscaler,
  webhook) to run and patch. We already operate KEDA for T2; the HTTP add-on reuses it,
  is lighter, and keeps one autoscaling system. Revisit Knative only if we need its
  traffic-management features.

### Promotion gate (measured, ADR-0054 style)

Promote `product-catalog` to T1 in `rules.yaml: finops_tiers.declared` **only if**, with
`minReplicas: 0`:
- native cold start (0 → first-byte) ≤ a stated budget (target ~300–500 ms p95), and
- steady-state p95 latency stays within the service SLO.
Otherwise it stays T0/`min>0`. The classifier already surfaces the candidate; this ADR
adds the *measured* gate before flipping the declared tier.

## Risk analysis

| Risk | Severity | Mitigation |
|---|---|---|
| **Cold-start latency on the first request** after idle | Med | Native start ~tens of ms; the interceptor parks the request (no 5xx), just adds latency to the first hit. Gate on a measured p95 budget before promoting. |
| **Native build gotchas** (reflection/resources not registered → runtime failure that JVM tests miss) | Med | A **native integration test** in the native CI lane; promote only on green. Quarkus 3.33.2 auto-registers most; explicit `@RegisterForReflection` where needed. |
| **Native build cost** (slow ~3–5 min, RAM-hungry ~4–8 GB; pulls the Mandrel builder) | Low–Med | Separate lane, not on the JVM PR gate; **note the FinOps trade-off — T1 trades runtime compute for build compute/NAT egress**, so pair with the Gradle cache (ADR-0058 sequencing). |
| **HTTP add-on interceptor is a new request-path hop / dependency** | Med | A failure of the interceptor breaks cold requests. Keep `minReplicas: 0` for *non-critical* reads only; **money-path stays T0** (never behind the interceptor). Run the interceptor HA (≥2). |
| **Cold-start cascade** (a sync chain of T1 calls sums cold-starts) | Med | ADR-0057 guardrail: the classifier flags deep sync call-graphs; keep an upstream node warm. `product-catalog` is a leaf read → no cascade. |
| **New platform component to operate** (HTTP add-on) | Low | Codified in tofu, feature-flagged, reversible; same operational model as KEDA/ARC. |
| **Cloud-agnostic** | None | KEDA HTTP add-on runs on any conformant Kubernetes (ADR-0027 intact). |

## Pilot plan (when approved — reversible)

1. Add the native build + native IT to `product-catalog` (PR, Sonnet GO). JVM image
   stays the default; native is opt-in via the new lane.
2. Build the native image, **measure cold start** in a scratch namespace (no traffic
   routing yet) — pure measurement, zero blast radius.
3. Install the KEDA HTTP add-on via tofu (feature-flagged) in the platform env.
4. Define `HTTPScaledObject` (`minReplicas: 0`) for `product-catalog`; route its ingress
   through the interceptor in the sandbox.
5. **Measure** p95 cold-start + steady-state under scale-from-zero for 48 h.
6. If SLO met → declare `product-catalog: T1` in rules.yaml (the classifier drift clears).
   If not → remove the `HTTPScaledObject`, route back direct, keep `min>0`. No harm.

## Alternatives considered

- **Knative Serving.** More features, heavier; rejected for the pilot to avoid a second
  autoscaling control plane (see above). Reconsider if traffic-splitting/revisions are needed.
- **Keep-warm `min=1` (status quo).** Zero new moving parts; but never removes the
  standing hours T1 exists to reclaim. That is the cost we are attacking.
- **AWS Lambda / Fargate.** Proprietary, breaks ADR-0027, different packaging for
  JVM/Quarkus — rejected in ADR-0057.
- **JVM image with CRaC / AppCDS instead of native.** Faster JVM start without GraalVM;
  lighter build. A credible fallback if native build proves too costly — worth a spike
  if the native lane is painful.

## Consequences

- Establishes the **first T1 recipe** (native build lane + HTTP add-on + measured gate)
  reusable across the read/HTTP fleet.
- Adds one platform component (HTTP add-on) and a native CI lane; build compute rises in
  exchange for runtime compute falling.
- **Money-path services never enter T1** (stay T0); the interceptor is for non-critical
  reads only.
- **This ADR applies nothing.** It is a proposal; the native-build PR, the tofu add-on
  install, and the cutover are separate, individually-reviewed follow-ups.
