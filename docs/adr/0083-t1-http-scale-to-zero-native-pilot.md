# ADR-0083 — T1 (HTTP → 0): native-image + KEDA HTTP add-on pilot on product-catalog

Date: 2026-06-02
Status: Accepted
Delivery-Status: Partial
Author(s): Jiri Raska

> **Correction (2026-07-05).** A prior edit (#2800, 2026-06-30) marked this ADR
> `Delivery-Status: Shipped` and declared `openbank-product-catalog: T1` in
> `rules.yaml` on the strength of Enabler 2 alone (the `HTTPScaledObject` + KEDA HTTP
> add-on) while citing a non-existent issue (`#2083`) for Enabler 1 (the native image).
> **No commit in this repo's history ever added a native build for `product-catalog`**
> — `git log --all -- openbank-product-catalog/Dockerfile` shows only JVM fast-jar
> changes (base-image digest pins). The service is still deployed as a JVM image
> today (see `openbank-infra/gitops/components/accounts/product-catalog.yaml`), which
> means the live `HTTPScaledObject` (`minReplicas: 0`) is currently scaling a ~1-2 s
> JVM cold start, not the ~tens-of-ms native cold start this ADR's promotion gate
> requires — the SLO was never actually measured, let alone passed. `Dockerfile.native`
> (this PR) closes Enabler 1 in code; the §"Promotion gate" measurement below is still
> open, and `rules.yaml:finops_tiers.declared` has been reverted to leave
> `product-catalog` unclassified until it is. Remaining sandbox deploy + measurement
> tracked in #253 — re-declare T1 only from that, not from this correction.
>
> **Local verification of `Dockerfile.native` (2026-07-05) found exactly the "native
> build gotcha" this ADR's own risk table warns about**, and it is NOT yet resolved:
> the image compiles and cold-starts fast (~200-330 ms, confirming the ADR's cold-start
> premise), but **every HTTP request — including `/q/health/live` and `/` — currently
> returns 500** once running (JVM behavior is unaffected; this is native-only). Root
> cause not yet isolated: `openbank-libs-runtime`'s JAX-RS filters
> (`ApiVersionResponseFilter`, `CorrelationIdFilter`, `RateLimitFilter`) look reflection-free
> on inspection, and no exception stack trace surfaces in the JSON console logger even at
> DEBUG (`quarkus.log.console.json` is build-time-fixed for native, so it can't be flipped
> at runtime to get one). No commit in this repo has ever exercised `openbank-libs` under
> GraalVM native before, so this may be a fleet-wide libs gap, not a product-catalog-only
> one. **This blocks Enabler 1 from being considered done** — tracked as the first item
> in #253 alongside the sandbox measurement, ahead of any deploy attempt.
>
> **Update (2026-07-06) — Enabler 1 bugs fixed; promotion gate still NOT met, for a
> different reason than expected.** Root-caused and fixed both native-only defects (JVM
> was unaffected by either — full detail in #253):
> 1. `PostgresProductRepository.coAwait()` bridged Mutiny `Uni` → Kotlin coroutine via
>    `subscribeAsCompletionStage().await()`, dropping the Vert.x duplicated context
>    RESTEasy Reactive needs to write the response — a hand-rolled bridge; every other
>    service in the fleet already uses the standard `mutiny-kotlin` `awaitSuspending()`
>    for this. Switched to match the fleet.
> 2. `Product` (JSONB-backed, deserialized via plain `ObjectMapper.readValue`, not a
>    direct JAX-RS body) was never registered for reflection, so Jackson's Kotlin
>    data-class Creator lookup failed under native with `InvalidDefinitionException`.
>    Added `@RegisterForReflection`.
>
> With both fixed, `/api/v1/products` returns 200 with real data and `/q/health/ready`
> on the management port returns `UP` with live DB connectivity — **the native image
> itself now works correctly.** Deployed it to an isolated scratch namespace in the
> sandbox cluster (own throwaway Postgres, no Ingress/HTTPScaledObject, zero blast
> radius) and measured 5 scale-0→1 cycles: **3.5 s, 4.8 s, 8.8 s, 5.2 s, 7.5 s** (mixed
> methodology — Pod-`Ready` time and Service-reachable time; both landed in the same
> multi-second range). This is **7-25× the ADR's ~300-500 ms p95 target**, and the gap
> is NOT the application — process start alone is ~0.2-0.3 s, confirmed repeatedly.
> **The gap is Kubernetes Pod-lifecycle overhead**: scheduling, image pull when a node
> hasn't cached the image (~3 s observed), and the kubelet's readiness-probe polling
> interval — none of which a fast-starting binary can shortcut. The scratch test used a
> *more aggressive* probe (`periodSeconds: 1`) than product-catalog's real gitops
> manifest (`periodSeconds: 10`, plus a `startupProbe` with `initialDelaySeconds: 5`) —
> so production would measure slower, not faster, than these numbers.
>
> **This means the ADR's promotion gate as written may be unmeasurable-as-stated for a
> Pod-scale-from-zero mechanism, regardless of image type.** The KEDA HTTP add-on
> interceptor hides this latency from the caller (parks the request, no 5xx — by
> design, per the risk table above), but the ADR's own §"Promotion gate" asks to
> "promote only if native cold start (0 → first-byte) ≤ ~300-500 ms p95," and that
> number was scoped to *process* start, not *Kubernetes Pod* start. `rules.yaml`'s
> `openbank-product-catalog` entry stays unclassified (not T1) — the SLO, as measured
> end-to-end, is not met. Whether to (a) redefine the promotion gate around what's
> actually controllable (process start) and treat multi-second interceptor latency as
> an accepted trade-off, or (b) treat this as evidence T1 isn't viable for this service
> without further platform work (node-level image pre-pulling, a faster probe) is a
> product decision, not something this correction resolves — recorded in #253.

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
KEDA HTTP add-on is installed (tofu/sandbox-platform, HA interceptor 2 replicas).
`HTTPScaledObject` for `product-catalog` is live (minReplicas: 0) — Enabler 2 is done.
Enabler 1 (native image) now has a build (`openbank-product-catalog/Dockerfile.native`
+ `.github/workflows/product-catalog-native-build.yml`, a non-blocking smoke-test lane),
but it has not been deployed to the sandbox or measured against the promotion gate below
— **the tier is NOT yet declared T1 in `rules.yaml`.** Until the measured cold-start SLO
passes in-cluster, the live `HTTPScaledObject` is scaling a JVM image from zero, which
does not meet this ADR's cold-start assumption; treat `minReplicas: 0` as running ahead
of its own prerequisite, not as a completed pilot.

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
