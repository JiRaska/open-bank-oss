# Serverless tier — scale-to-zero on the existing cluster, not FaaS

Date: 2026-05-31
Status: Accepted
Author(s): Jiri Raska

## Context

We are sizing for millions of customers. A fair share of the ~30-service fleet is
**not** steady-state request traffic: periodic batch (interest accrual, end-of-day
reconciliation, statement/report generation), event-driven async work (notifications,
webhooks, FX-rate refresh, AML batch analysis), and **spiky** onboarding/compliance
bursts (KYC/OCR, sanctions screening) that peak during marketing campaigns and sit
near-idle the rest of the day. For these, paying for peak-sized capacity 24/7 is
waste. The question is whether a *serverless / scale-to-zero* execution tier earns
its place, and in what form.

Forces at play:

- **Cost at scale.** Auxiliary and bursty services are provisioned for peak but used
  a fraction of the day. The structural waste is the gap between *provisioned peak ×
  24/7* and the *integral of actual demand*.
- **Cloud-agnostic substrate (ADR-0027).** Everything stateful runs in-cluster on OSS;
  no managed-cloud lock-in. AWS Lambda / Step Functions are AWS-specific and would
  cut directly against this line.
- **Current runtime reality — JVM, not native.** All 30 services ship as JVM uber-jars
  (`eclipse-temurin:25-jre`, `quarkus-run.jar`, ZGC). Quarkus JVM cold-start is
  ~1.5–4 s (plus pool warm-up, Flyway check, JIT). Scale-to-**zero** over a JVM
  service is therefore a latency footgun unless a native image is built first or a
  warm floor is kept — and a warm floor erases most of the saving.
- **Money-path predictability (rules.yaml, ADR-0030).** 13 of ~30 services are
  money-path (ledger, transaction, account, balance, sepa-payment, sepa-instant,
  domestic-payment, clearing, swift, fx, lending, sca, consent). They carry steady
  load, tight p99 (card auth ~ms, SEPA-instant ~10 s SLA), and audit-driven capacity
  expectations. Cold-start jitter and "scaled to zero when a regulator probed it" are
  unacceptable there.
- **Control-plane economics precedent (ADR-0040).** We rejected EKS-just-for-CI partly
  because an EKS control plane does not itself scale to zero. The platform EKS cluster
  already exists for runtime (ADR-0027), so adding Knative/KEDA as **operators on an
  existing cluster** is a marginal, sunk-cost addition — not a new always-on bill.
- **Operational + regulatory surface.** A bank threat-models and certifies every moving
  part. A new autoscaling control plane (Knative Serving: activator + autoscaler +
  ingress) is real surface; it must be justified by realized savings, not theoretical.

Honest sizing of the prize: the platform bill is dominated by the data layer
(Kafka, Postgres-per-service per ADR-0009) and the 13 always-on money-path services —
none of which serverless touches. Realistic whole-platform **compute** saving is
**~5–15 %**, concentrated in the candidate subset, and only after native-image work or
accepting warm floors. The earlier "15–25 %" estimate over-counted by pricing reserved
capacity at 24/7 while ignoring HA minima, node bin-packing, and warm floors.

## Decision

**We will introduce a serverless execution *tier* using scale-to-zero / event-driven
autoscaling on the existing EKS cluster (ADR-0027), choosing the simplest tool that
fits each workload, and we will NOT adopt managed FaaS (Lambda/Step Functions).
Money-path services are explicitly excluded and remain always-on.**

Concretely:

1. **Three placement tiers, cheapest sufficient tool first:**
   - **Tier 1 — periodic batch → native Kubernetes `CronJob`/`Job`.** Interest accrual,
     EOD reconciliation, statements, reports. This already *is* scale-to-zero economics
     with **no new control plane**. It is the cleanest, lowest-risk, do-it-now saving.
   - **Tier 2 — event/async → `KEDA`, scaling 1→N (floor of 1, not to zero).**
     Notifications, webhooks, FX refresh, AML batch. A floor of 1 avoids first-event
     cold-start; KEDA scales on Kafka consumer lag / queue depth.
   - **Tier 3 — spiky HTTP onboarding → `Knative` scale-to-zero, ONLY after a native
     image exists for that candidate.** KYC/OCR, sanctions on onboarding spikes.
     Without a native image the floor stays at 1 and the saving is small — so the
     native build is a *precondition*, recorded as such.

2. **Money-path services (rules.yaml `money_path_services`) stay always-on.** No
   scale-to-zero, no cold-start exposure, predictable capacity for audit.

3. **A service flips tiers only on measured evidence, against a fixed rule:**
   migrate to scale-to-zero iff `idle_ratio > 50 % AND cold_start_p99 < service_SLA_budget
   AND peak:avg > 3 AND service ∉ money_path_services`; otherwise it stays 24/7. Both
   modes are measured side-by-side for one week before the flip.

4. **Decision metrics are instrumented and recorded** (the same set used to justify
   each flip): idle ratio, cold-start p99 (0→1 ready), peak:average RPS, busy-time
   utilization, realized-vs-reserved vCPU-hours, added p99 tail (warm vs cold),
   drop/queue rate during 0→N transitions, and SLO breach / error-budget burn during
   scale events.

5. **Pilot first, fleet-wide later.** Start with two non-money-path, clearly bursty
   services — **interest accrual (Tier 1)** and **notifications (Tier 2)** — to get a
   *real* saving number for our traffic before committing Tier 3 / native work.

KEDA 2.x is deployed cluster-wide (confirmed: notification-service ScaledObject live,
Kafka trigger idle->0 working). Phase 1 pilot complete. Phase 2 HTTP trigger pilot
tracked in ADR-0083.

## Alternatives considered

- **Managed FaaS (AWS Lambda + Step Functions).** Lowest ops, true scale-to-zero,
  per-request billing. **Rejected:** AWS lock-in directly violates ADR-0027; at
  millions of customers the per-invocation price on high-volume paths can exceed
  always-on pods (the break-even is why money-path stays always-on anyway); cold-start
  on JVM is worse, and rewriting to a FaaS packaging model is large, lock-in-increasing
  work.

- **Knative scale-to-zero for everything (incl. event/async).** Uniform model.
  **Rejected:** over-engineered for two of three tiers — periodic batch is already
  served by `CronJob` with zero new control plane, and event/async is served by KEDA
  with a floor of 1. Scaling async consumers to literal zero just reintroduces
  cold-start on the first event. Reserve Knative for genuine HTTP request-driven
  spikes.

- **Do nothing — keep all services always-on, tune requests + cluster autoscaler.**
  Simplest, no new surface. **Partially kept, not chosen as the whole answer:** good
  bin-packing already amortizes some idle, but it cannot reclaim the 24/7 cost of
  services idle 85–90 % of the day, and it leaves the spiky-onboarding peak:average gap
  on the table. The pilot will quantify whether the Tier-1 `CronJob` saving alone is
  worth the rest.

- **Build native images fleet-wide to make scale-to-zero cheap everywhere.**
  Tempting given Quarkus. **Deferred:** native build is slow, GraalVM reflection
  config is fragile, and not every dependency is native-friendly. We do it *only* for
  Tier-3 candidates that the metrics prove worth it — not speculatively.

## Consequences

**Positive**
- **Concentrated compute saving (~5–15 % platform, higher on the candidate subset)**,
  with Tier-1 `CronJob` capturing the cleanest part at near-zero risk and no new
  control plane.
- **No new cloud lock-in** — stays on the ADR-0027 substrate; the autoscalers are
  operators on the already-existing EKS cluster.
- **Evidence-driven**, not faith-driven: every flip is backed by a measured metric set
  and a fixed rule, so the tier can be defended in review and to auditors.

**Negative**
- **New control-plane surface** (Knative Serving for Tier 3, KEDA for Tier 2) to patch,
  monitor, and threat-model.
- **Cold-start risk on JVM** makes Tier 3 contingent on native-image work; without it
  the saving there is marginal.
- **Operational complexity in debugging** — first-request-after-idle latency and
  tracing gaps across scale-from-zero make incident analysis harder.
- **Estimate is soft** until the pilot lands; realized cash savings may come in at the
  low end after HA minima and warm floors.

**Neutral**
- The data layer (Kafka, Postgres-per-service) and money-path steady load — the bulk of
  the bill — are unaffected; this ADR optimizes a minority of compute by design.
- Tier boundaries will move as services change shape; the flip rule, not a static list,
  is the source of truth.

## Compliance impact

- PCI DSS: not applicable (no cardholder data introduced; card-auth stays always-on).
- DORA: **applicable** — scale-to-zero changes availability/recovery characteristics of
  affected services; cold-start and scale-transition behaviour become documented
  operational properties, and money-path exclusion preserves predictable capacity.
- GDPR: not applicable (no change to personal-data processing or storage).
- PSD2: **applicable** — onboarding/SCA-adjacent flows must keep their latency SLAs;
  SCA itself is money-path and excluded. Tier-3 candidates touching PSD2 paths require
  the metric gate before any flip.
- CNB: not applicable.

## References

- ADR-0027 — cloud-agnostic substrate (everything stateful in-cluster OSS; no FaaS lock-in).
- ADR-0040 — CI execution model and cost (scale-to-zero / control-plane economics precedent).
- ADR-0010 — Kubernetes + ArgoCD GitOps (where Knative/KEDA operators are deployed).
- ADR-0009 — Postgres-per-service (data-layer cost that serverless does not address).
- ADR-0030 — supply-chain security / SSDLC (new control-plane surface to threat-model).
- `openbank-libs/governance/rules.yaml` — `money_path_services` (the always-on exclusion list).
