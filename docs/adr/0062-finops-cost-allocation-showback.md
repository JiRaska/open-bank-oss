# ADR-0062 — FinOps cost allocation: requests-weighted showback by service, domain and business flow

Date: 2026-06-03
Status: Accepted
Author(s): Jiri Raska

## Context

ADR-0054 (phase 2) gave the operator console real cloud spend: a daily AWS Cost Explorer
snapshot, baked/served read-only by `/api/finops/costs`. But Cost Explorer aggregates by **AWS
service** — "EC2 $138, EKS $10, S3 $12". An operator can see *that* we pay for EC2, not *what the
bank pays for*: which OpenBank service, which cost-center (domain), which business flow. That is
the layer FinOps actually steers on — and the one missing.

The taxonomy needed to answer it already exists in the repo, unused for cost:
- **Domain (cost-center)** — `dataDomain` per service in the admin-ui governance manifest
  (`core`/`payments`/`compliance`/`identity`/`open-banking`/`platform`).
- **Cost-driver** — `resources.requests` (cpu/memory) declared in the gitops Deployment manifests.
- **Tiers / money-path** — `rules.yaml: finops_tiers`, `money_path_services`.

What was missing: (a) a machine-readable **business-flow → services** mapping (it lived only in
`docs/strategy` prose), and (b) an **allocation engine** to join cloud spend × cost-driver ×
taxonomy. The admin-ui must stay a read-only consumer (root CLAUDE.md rule #3): it never holds
billing IAM and never produces governance data.

## Decision

**We add requests-weighted showback: the shared compute pool is split across services by their
declared resource requests, then rolled up to domain (cost-center) and business flow. The
breakdown is derived (rule #7) and displayed read-only on `/finops/allocation`.**

1. **Allocation model (OpenCost-style, requests-based).**
   - Classify each Cost Explorer line **explicitly** (not heuristically): `COMPUTE` (EC2 instance
     hours = the worker nodes pods run on) is the allocatable pool; everything else — EKS control
     plane, ELB, Route53, CloudWatch, S3, and the ambiguous **"EC2 - Other"** (NAT/EBS/EIP) — is
     **platform overhead**, shown but never split. The ambiguous line is treated conservatively as
     overhead so per-service numbers are never inflated by costs we can't attribute to a pod.
   - `weight(svc) = 0.5·(cpu share) + 0.5·(ram share)` — the OpenCost default of splitting cost
     equally between a CPU pool and a RAM pool, normalised. `allocated$(svc) = COMPUTE × weight`.
   - **Invariant:** `Σ byService == allocatable`, and `allocatable + platformOverhead == total`.

2. **Three lenses.** `byService` ($/service), `byDomain` (sum by `dataDomain`, the cost-center),
   and `byFlow`. Flows are **fully-loaded**: a service belongs to every flow that needs it, so the
   per-flow sums **overlap and can exceed the total** — intentional ("cost to run this flow
   end-to-end, including shared dependencies"). The UI states this explicitly.

3. **Sources of truth.** Business-flow → services is authoritative in
   `rules.yaml: finops_cost_groups` (mirrors the `finops_tiers` style). Domain stays in the
   governance manifest (no second table to rot). Cost-driver footprints are **derived at build**
   by `collect-cost-footprints.mjs` walking the gitops requests → `cost-footprints.json` (baked,
   like `catalog.json`/`service-graph.json`). The join key is the **manifest serviceName** (no
   `openbank-` prefix), which equals the gitops Deployment name — `money_path_services` uses a
   different `openbank-*` convention and is deliberately **not** the join key here.

4. **Engine placement (hybrid).** Footprints are derived at build (no live cluster access);
   `cost-report.json` stays live>baked-fresh; the route joins them at **runtime** through a pure
   `allocate()` function. Pure ⇒ unit-testable without a cluster or AWS. A drift test
   (`finops-taxonomy.test.ts`) fails the build if a flow names a service unknown to the manifest
   (derive → enforce → show, ADR-0029).

5. **Honest by construction.** Missing snapshot → `available:false` → calm `DataUnavailable`
   (never a fabricated number, never a raw error). A service with no footprint (not deployed) gets
   no row, not a default weight. In-cluster infra not in the manifest (keycloak, redis, …) is
   allocated under `platform` and surfaced in an `unmapped` notice rather than silently folded into
   business services.

## Alternatives considered

- **Deploy OpenCost / Kubecost.** The industry tool for this. Rejected for the sandbox: a net-new
  pod + Prometheus scrape + cloud-billing integration against a $300/mo, lean-FinOps substrate
  (single-replica Prometheus, 12h retention). The requests-based maths it would run is small enough
  to derive ourselves with zero new runtime surface and no new cloud IAM. OpenCost stays the
  natural upgrade path if per-pod actuals ever matter more than declared requests.
- **Usage-based weighting (live Prometheus CPU/RAM) instead of requests.** More accurate, but
  non-deterministic and empty for the ~17 services not deployed in the sandbox — the allocation
  would shift hour to hour and couldn't be unit-tested. Requests are declarative, deterministic,
  reproducible per commit; usage-based is a future refinement, not the v1.
- **Hand-maintained footprint table in the UI (like serverlessTiers.ts).** Rejected by rule #7:
  resource requests are a measurable fact already authoritative in gitops, so they must be derived,
  not duplicated. (serverlessTiers is hand-maintained only because it mirrors a *human policy*.)

## Consequences

**Positive**
- The operator sees spend in the bank's own taxonomy (service / domain / flow), not just AWS SKUs.
- Pure `allocate()` + a drift test make the maths and the taxonomy CI-verifiable.
- Zero new runtime/cloud surface: derive at build, join read-only at runtime.

**Negative**
- Requests-weighted ≠ actual utilisation: an over-provisioned service is charged for its request,
  not its usage. Accepted for v1; documented in the UI. Usage-based weighting is the upgrade.
- `finops_cost_groups` is hand-curated and must track real flows; the drift test guards membership
  validity but not completeness (a missing flow is silent) — `log`-style omissions noted in review.

**Neutral**
- In the sandbox only ~11 deployments carry requests, so allocation covers running services only
  (honest: you pay for what runs). Coverage grows as the fleet deploys.

## Compliance impact

- **FinOps / cost stewardship:** spend is now attributable to service, cost-center and regulated
  flow (e.g. "SEPA Credit Transfer", "Compliance Gate") — a governed, derived view, not tribal
  knowledge.
- **PCI/GDPR/PSD2/CNB:** unchanged — the view is cost/metadata only, no CDE or personal data; the
  admin-ui holds no billing IAM (rule #3).

## References

- ADR-0054 — FinOps guardrails: managed-service version lifecycle + cost audit (the cost snapshot).
- ADR-0057 — scale-to-zero workload tiers (the other FinOps lever; T0–T3).
- ADR-0029 — governance as code (derive → enforce → show); the pattern this follows.
- `openbank-libs/governance/rules.yaml` — `finops_cost_groups` (this ADR).
- `openbank-admin-ui/scripts/collect-cost-footprints.mjs` — footprint collector.
- `openbank-admin-ui/src/lib/finops/allocation.ts` — the pure `allocate()` function.
- `openbank-admin-ui/src/app/finops/allocation/page.tsx` — the read-only view.
