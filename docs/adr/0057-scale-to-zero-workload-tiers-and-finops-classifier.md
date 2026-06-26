# Scale-to-zero workload tiers and a FinOps classifier

Date: 2026-06-01
Status: Accepted
Author(s): Jiri Raska

## Context

ADR-0027 fixed the substrate as a **cloud-agnostic, in-cluster OSS** stack: every
stateful dependency runs inside Kubernetes, no proprietary managed service in the
critical path. ADR-0053 then proved the FinOps thesis on CI — the dominant cost
lever at our utilisation is **running hours**, and only *scale-to-zero* removes
them. CI is now per-job ephemeral ARC runners on Karpenter's Graviton spot pool,
idle-cost ≈ $0.

The application fleet has not had the same treatment. Live inspection of the
sandbox (2026-06-01) shows every deployed service is a single-replica **always-on
`Deployment` (`1/1`)** — `account-service`, `product-catalog`, `balance-service`,
`ledger-service`, `admin-ui` — each pinning a Karpenter node 24/7. The sandbox is
*bursty and mostly idle* (nights, weekends, between demos), exactly the profile
ADR-0053 measured for CI. Always-on replicas hold nodes that Karpenter would
otherwise consolidate to (near) zero, so we pay for ~30 services' worth of standing
compute to serve a workload that is idle most of the day. As the fleet grows from
the ~5 deployed today toward the ~28 services, this becomes the dominant
non-data-plane cost.

"Go serverless" is the obvious FinOps reflex, but the naive reading — AWS
Lambda / Fargate — is **wrong for this bank**: it is proprietary (breaks ADR-0027's
cloud-agnostic mandate), changes the runtime/packaging model, and adds a vendor
surface to a regulated money path. The opportunity is narrower and already paid
for: **scale-to-zero *on the Kubernetes substrate we already run*, on the Karpenter
spot pool we already shop.** Quarkus is built for this — a GraalVM **native**
image cold-starts in tens of milliseconds, which is what makes scale-to-zero viable
for request-latency-sensitive services rather than only background jobs.

Two things are missing to act on this safely:

1. **A decision rule.** Not every service may scale to zero. A money-path service
   on a synchronous payment hop cannot eat a cold-start, and a regulator may mandate
   continuous availability. We need an explicit, defensible classification — not an
   ad-hoc per-service guess.
2. **A way to keep the classification honest.** Per ADR-0029 (govern-as-code) and
   ADR-0054 (FinOps as a CI gate), a hand-maintained "which services are serverless"
   list would rot. The tier must be *derivable from measured behaviour* and
   *shown/enforced*, not asserted once in a wiki.

Forces: cloud-agnostic substrate (ADR-0027); $0-idle FinOps (ADR-0053/0054);
money-path availability + supply-chain integrity (ADR-0030, DORA ICT availability);
govern-as-code / derive→enforce→show (ADR-0029). What changed is that the CI proof
(ADR-0053) plus a Karpenter spot substrate make the same lever available to app
workloads at near-zero marginal infra.

## Decision

**We will classify every service into one of four FinOps workload tiers, default
new services to scale-to-zero, and keep the tier derivable from measured traffic
rather than hand-assigned. Scale-to-zero is implemented on the existing Kubernetes
substrate (KEDA, optionally Knative Serving) on Karpenter's spot pool — never on a
proprietary FaaS — so ADR-0027 is preserved.**

### The four tiers

| Tier | Definition | Mechanism | Idle cost |
|------|------------|-----------|-----------|
| **T0 — Always-on** | Money-path *hot* path where a cold-start or a scaled-to-zero gap is unacceptable; or a regulator-mandated continuous service. | `minReplicas ≥ 1` (the min-replica guarantee; a PodDisruptionBudget *complements* it for voluntary-disruption cover but is not itself the floor); never scales to zero. | Full |
| **T1 — HTTP → 0** | Stateless request/response, bursty or rare traffic, cold-start-tolerant within its latency SLO. | KEDA HTTP add-on (or Knative Serving) — scale from/to zero on inbound HTTP. | ~0 when idle |
| **T2 — Event → 0** | Async Kafka consumers / projections / dispatchers that tolerate processing latency. | KEDA `ScaledObject` on **Kafka consumer-group lag** — wake on backlog, drain, return to zero. | ~0 when topic idle |
| **T3 — Cron / Job** | Periodic batch (reconciliation, audits, accruals) with no resident listener. | Native K8s `CronJob` / `Job`. | 0 (no resident pod) |

The classification axes, applied in order:

1. **Money-path + availability** — is continuous service mandated (regulatory) or is
   the path a *synchronous* money hop where cold-start latency is unacceptable? → **T0**.
   (Money-path services per `rules.yaml: money_path_services`; a T0→lower move needs a
   threat-model update per ADR-0030.)
2. **Trigger** — periodic with no listener → **T3**; async event-driven → **T2**;
   synchronous HTTP → candidate **T1**.
3. **Traffic shape + latency SLO** — constant high QPS or a tight p95 that the
   native cold-start budget cannot meet → keep **T0/min>0**; bursty/rare and
   cold-start-tolerant → **T1**.

**Default for new services is the lowest tier its trigger allows** (T2 for event
consumers, T1 for HTTP), not T0. Always-on is opt-in and justified, reversing
today's implicit always-on default.

### Best first target

**T2 (event consumers) is the highest-ROI, lowest-risk entry point:** async
consumers already tolerate latency, so scaling to zero on Kafka lag is invisible to
callers, and the outbox/projection services are off the synchronous money path.
T1 (HTTP) follows on a pure read service. **The money-path `ledger` synchronous hop
stays T0 and is explicitly not an early pilot.**

### Keep it honest — a FinOps classifier (govern-as-code, ADR-0029 / ADR-0054)

The tier is **derived from data, not hand-assigned**:

- A classifier reads measured signals per service — HTTP request rate / idle ratio,
  Kafka consumer-group lag and active fraction, CPU/replica utilisation — from the
  cluster's metrics, and emits a **recommended tier** plus the realised idle-hours
  saving.
- This recommendation is surfaced (admin-ui FinOps view) and **CI-checked the same
  way ADR-0054 gates version lifecycle**: a service whose declared tier diverges from
  its measured behaviour (e.g. a T0 that is idle 95% of the time with no money-path
  justification) raises a FinOps finding / tracking issue (ADR-0052), not a silent
  drift.
- The lever to *raise* a service back toward `min>0` is an explicit, **measured p95
  queue/latency SLO miss** — a number, not a hunch. `minReplicas` during business
  hours is the documented escape hatch, off by default for cost.

### Guardrails

- **Cold-start cascade.** A chain of synchronous T1 calls sums cold-starts. The
  classifier flags sync call-graph depth; deep synchronous chains keep an earlier
  node warm (`min>0`) or pre-pull/keep-warm rather than all scaling to zero.
- **Native build budget.** T1 viability assumes Quarkus **native** images; the
  cold-start SLO is measured per service, not assumed.
- **Money path is sacred.** T0 membership is driven by `rules.yaml`; demoting a
  money-path service requires the ADR-0030 threat model + 2 approvals.
- **Cloud-agnostic preserved.** KEDA and Knative run on any conformant Kubernetes;
  no AWS-only primitive enters the path (ADR-0027 intact).

### Governed as code

KEDA (and Knative if adopted) install via OpenTofu in the platform env, the same
way ARC landed (ADR-0053). Per-service tier + scaling config live in the service's
own manifests; `rules.yaml: finops_tiers` records the policy and CI enforces the
declared-vs-measured check.

## Alternatives considered

- **AWS Lambda / Fargate (true FaaS).** Genuine scale-to-zero with no cluster to
  run. Rejected: proprietary (breaks ADR-0027 cloud-agnostic), forces a different
  packaging/runtime and cold-start profile for JVM/Quarkus, and adds a vendor
  surface to a regulated money path — for a saving we already capture on Karpenter
  spot at near-zero marginal infra.
- **Keep everything always-on, shrink instance sizes / bin-pack harder.** Simple, no
  new controller. Rejected: bin-packing lowers the *per-replica* cost but never
  removes the *running hours* — the dominant lever ADR-0053 measured. Idle still pays.
- **Scheduled scale-down (cron the sandbox to zero at night).** Cheap to add.
  Rejected as the *primary* model: it is a blunt time-based proxy for the real
  signal (no traffic), breaks ad-hoc/off-hours use, and does not generalise to
  per-service bursty load. Useful only as a coarse backstop, subsumed by per-service
  scale-to-zero.
- **Hand-maintained "serverless services" list.** Zero tooling. Rejected: it rots
  (rule #7 — derived data is never hand-edited); the whole point of the classifier
  is to keep the tier tied to measured behaviour.

## Consequences

**Positive**
- Idle app-compute trends toward $0 in the sandbox (and any low-traffic env): nodes
  consolidate to zero on the spot pool we already pay for — the ADR-0053 lever
  applied to the whole fleet.
- New services default to scale-to-zero, so the cost stays bounded as the fleet
  grows to ~28 services instead of multiplying always-on replicas.
- Tier is auditable and self-correcting (declared-vs-measured CI gate), not tribal
  knowledge.
- ADR-0027 cloud-agnostic posture is preserved; no FaaS lock-in.

**Negative**
- Cold-start tax on T1/T2 first-hit after idle. When the node pool has fully
  consolidated to zero, the first hit pays **Karpenter node provisioning (tens of
  seconds)** *on top of* the app's native cold-start (tens of ms) — the worst case is
  node-provisioning-bound, not app-bound. Mitigated by Quarkus native images,
  Karpenter `consolidateAfter` keeping a node briefly warm across bursts, and the
  `min>0` escape hatch on measured SLO miss — but not zero. The implementation
  follow-up sets the cold-start SLO against this node-provisioning worst case.
- New moving parts (KEDA, optionally Knative, the classifier) to operate and secure.
- Risk of mis-tiering a latency-sensitive path; bounded by the money-path→T0 rule
  and the SLO-driven re-warm lever.

**Neutral**
- T0 services are unchanged by this ADR beyond making their always-on status an
  explicit, justified choice rather than the silent default.
- Build pipeline gains a native-image expectation for T1 candidates (already a
  Quarkus capability).

## Compliance impact

- PCI DSS: not applicable (no CHD scope change; availability/scaling only).
- DORA:    ICT availability — T0 tier + money-path guardrail keep mandated-continuous
           services always-on; scaling policy is an operational resilience control,
           governed as code.
- GDPR:    not applicable (no change to data residency or processing; substrate stays
           in-cluster per ADR-0027).
- PSD2:    money-path synchronous hops remain T0 (no cold-start on the payment path);
           demotion gated by ADR-0030 threat model.
- CNB:     not applicable directly; continuity of regulated services preserved via T0.

## References

- ADR-0027 — cloud-agnostic, in-cluster OSS substrate
- ADR-0029 — governance as code (derive → enforce → show)
- ADR-0030 — money-path threat models
- ADR-0053 — ephemeral scale-to-zero ARC runners (the FinOps proof for CI)
- ADR-0054 — FinOps managed-service version lifecycle + cost audit
- `openbank-libs/governance/rules.yaml` — `money_path_services`, `finops_tiers`
- KEDA (`ScaledObject`, Kafka lag scaler, HTTP add-on); Knative Serving
