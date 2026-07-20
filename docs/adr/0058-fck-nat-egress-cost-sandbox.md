---
date: 2026-06-02
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [finops, networking, infrastructure]
summary: "The sandbox replaces the managed NAT Gateway with fck-nat behind an egress_mode toggle on the network module to cut NAT data-processing cost; production and HA-sensitive environments keep the managed NAT Gateway."
---

# Replace the managed NAT Gateway with fck-nat in the sandbox

## Context

A live cost review (2026-06-02) found that **AWS Activate credits stopped applying on
2026-06-01** (May was credit-covered to ~$0 net; June bills real money). With the
account now paying, the single largest *controllable* line is **`EC2 - Other`**, and
within it the **NAT Gateway**:

| EC2-Other usage type (1-day MTD sample) | $/day |
|---|---|
| `EUN1-NatGateway-Bytes` (data **processing**, $0.045/GB) | ~$25 |
| `EUN1-DataTransfer-Regional-Bytes` | ~$10.5 |
| `EUN1-NatGateway-Hours` ($0.045/hr) | ~$1.1 |
| `EUN1-EBS:VolumeUsage.gp3` | ~$0.5 |

> ⚠️ The sample is a single, front-loaded month-start day; treat the absolute monthly
> projection as order-of-magnitude, not a bill. The *shape* (NAT-processing dominates
> EC2-Other) is robust regardless.

The managed NAT Gateway charges **twice**: a fixed hourly (~$33/mo) **and** a
per-gigabyte **processing fee** ($0.045/GB) on every byte it forwards. AWS VPC
endpoints already keep AWS-API and ECR-layer/S3 traffic **off** the NAT (verified: the
S3 gateway endpoint is on the private route table that all three private subnets use,
and ECR `api`/`dkr` interface endpoints exist). So the NAT bytes are genuinely
**public-internet egress**: Maven Central / Gradle plugin-portal dependency downloads,
`public.ecr.aws`, and GitHub Actions traffic — driven by CI, and amplified by
full-fleet rebuilds.

The processing fee is the part we can remove. **fck-nat** (a maintained NAT-instance
AMI) provides the same outbound NAT function from a small EC2 instance and charges **no
per-GB processing fee** — only the instance hours (a `t4g.nano` ≈ $3/mo) plus ordinary
EC2 data-transfer, which is materially cheaper than NAT processing.

The current topology is a **single** managed NAT Gateway (`aws_nat_gateway.this` in
`modules/network/main.tf`) in one public subnet, with **one** private route table whose
`0.0.0.0/0` points at it, shared by all three private subnets. The module already
documents the deliberate single-AZ NAT choice for FinOps. This 1:1 shape makes an
fck-nat swap clean.

## Decision (proposed)

**Introduce an `egress_mode` toggle on the network module — `managed_nat` (default,
prod-grade) or `fck_nat` — and set the sandbox env to `fck_nat`. Production and any
HA-sensitive env keep the managed NAT Gateway unchanged.** This is sandbox-scoped cost
engineering, not a substrate-wide change; ADR-0027 (cloud-agnostic, in-cluster OSS) is
unaffected — fck-nat is a standard EC2 instance, no proprietary primitive.

### Shape (illustrative — not applied by this ADR)

```hcl
variable "egress_mode" {
  type    = string
  default = "managed_nat"           # prod-safe default
  validation { condition = contains(["managed_nat", "fck_nat"], var.egress_mode) }
}

# managed NAT GW: count = var.egress_mode == "managed_nat" ? 1 : 0   (unchanged)
# fck-nat:        count = var.egress_mode == "fck_nat"     ? 1 : 0
#   - a t4g.nano in public subnet[0], source/dest check DISABLED, the existing EIP
#     reattached to its ENI, fronted by a 1-instance ASG for auto-recovery.
# private RT 0.0.0.0/0 -> (managed) nat_gateway_id | (fck) network_interface_id
```

Prefer the well-known `RaJiska/fck-nat` Terraform module (ASG-backed auto-healing,
CloudWatch recovery) over a hand-rolled instance, so failover and patching are not
bespoke.

## Savings

- **Removes** the NAT-processing fee ($0.045/GB) — the dominant EC2-Other component —
  and the managed hourly (~$33/mo).
- **Adds** a `t4g.nano` (~$3/mo) + ordinary EC2 data transfer.

### Critical re-assessment (the headline number is softer than it first looks)

A first pass put this at ~$350–600/mo. Two corrections pull it down:

1. **The sample day is not steady-state.** The $25/day NAT-processing figure is from
   2026-06-01 — a day on which **three full-fleet CI rebuilds** ran (each pulls Maven /
   Gradle deps for ~28 services). That is an atypically heavy egress day, so it
   over-states the steady-state NAT throughput.
2. **fck-nat does not remove `DataTransfer-Regional-Bytes`** (~$10.5/day). That is
   cross-AZ transfer, billed regardless of NAT type — I initially lumped it into "NAT
   egress." Only the `NatGateway-Bytes` processing + hours are addressable here.

**Revised honest estimate: ~$150–350/mo, high uncertainty.** Still worthwhile, but not
the slam-dunk the first figure implied. The Budget + Cost Anomaly guardrail (already
live) will give the real run-rate within days — **decide on the real number, not this
1-day projection.**

### Sequencing (why this is NOT the first thing to do)

- **Renewing AWS Activate credits zeros the entire bill while active** — an order of
  magnitude more than this lever. fck-nat is **credit-conservation**, only worth the
  cutover risk once the credit position is known.
- **The Gradle remote build cache attacks the same NAT bytes at lower risk** — it cuts
  the *volume* of dependency re-downloads (and CI compute), without touching the egress
  path or introducing a NAT SPOF. Prefer it *before* fck-nat.
- Net recommendation: **credits → Gradle cache → fck-nat last**, and only cut over with
  an explicit acceptance of the Med-severity cutover risk below.

## Risk analysis

| Risk | Severity | Mitigation |
|---|---|---|
| **Single instance = SPOF** (no native multi-AZ HA) | Med | Same single-AZ blast radius the managed NAT already has here (one GW, one AZ). 1-instance **ASG** + CloudWatch auto-recovery replaces a failed node in ~2–3 min. Sandbox SLO tolerates this. |
| **Throughput ceiling** (instance NIC bandwidth vs NAT GW's 45 Gbps) | Low | `t4g.nano` ≈ up to 5 Gbps burst — far above CI egress. Size up the instance if a burst saturates; the toggle makes that a one-line change. |
| **Patching / OS maintenance** of the NAT instance | Low | fck-nat AMI is purpose-built and minimal; ASG relaunch picks up new AMIs. Far less surface than a general host. |
| **Cutover causes egress outage** (in-flight CI loses internet) | Med | Reversible single-attribute flip; do it in a quiet CI window; rollback = flip `egress_mode` back to `managed_nat` and re-apply (~2 min). |
| **source/dest check / routing misconfig** | Low | Handled by the upstream module; validate with a `curl` egress test from a private-subnet pod before declaring done. |
| **Security posture change** (instance vs managed service) | Low | Egress-only, no inbound; SG denies all inbound except established. Still inside the isolated VPC (ADR-0027). No data-plane/regulated path touches it. |
| **Prod blast radius** | None | Toggle defaults to `managed_nat`; only the sandbox env opts in. Prod is untouched. |

## Cutover plan (when approved — reversible, ~10 min)

1. Land the module toggle + sandbox `egress_mode = "fck_nat"` as a PR (Sonnet GO).
2. `tofu plan` — expect: create fck-nat (instance/ASG/ENI), re-point private RT
   `0.0.0.0/0`, destroy the managed NAT GW. Review the plan explicitly.
3. Apply in a **quiet CI window** (no fleet rebuild running).
4. **Verify**: from a pod in a private subnet, `curl -sI https://github.com` and a
   `gradle`/Maven fetch succeed; KEDA/ArgoCD reconcile; CI job egress works.
5. Watch the Budget/anomaly + `EC2-Other` line for 48 h to confirm the drop.
6. **Rollback** (any failure): flip `egress_mode` back to `managed_nat`, re-apply.

## Alternatives considered

- **Keep managed NAT, attack the bytes instead** (Gradle remote build cache so the
  fleet stops re-downloading deps; ECR pull-through cache for `public.ecr.aws`). Real
  and complementary, but caps the *volume*, not the *per-GB fee* — and standing up a
  cache node is its own infra. Pursue **in addition**, not instead. (Tracked separately.)
- **Per-AZ NAT or NAT GW as-is.** Status quo; the line we are trying to cut.
- **VPC endpoints for everything.** Already done for AWS APIs; Maven/GitHub/`public.ecr.aws`
  are not AWS services, so endpoints can't cover them.

## Consequences

- Sandbox egress runs through a self-managed (but module-managed) NAT instance; one more
  EC2 instance to exist, offset by removing the processing fee.
- Establishes the `egress_mode` seam so any future cost-sensitive non-prod env can opt in,
  while prod stays managed-NAT by default.
- **This ADR applies nothing.** It is a proposal for approval; implementation is a
  follow-up PR with its own plan/review/cutover.

## Compliance impact

- PCI DSS: not applicable — sandbox outbound network path, no cardholder data.
- DORA:    engaged — the swap changes the sandbox egress availability posture (single-instance SPOF, ASG auto-recovery, reversible cutover); specific articles not mapped in this ADR.
- GDPR:    not applicable — egress-only NAT, no personal data processed.
- PSD2:    not applicable — infrastructure cost change, no payment interface.
- CNB:     not applicable — sandbox-scoped; prod keeps the managed NAT Gateway.
