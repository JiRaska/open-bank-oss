---
date: 2026-06-01
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [finops, infrastructure, ci]
summary: "Any pinned managed-service version must be on standard support with at least six months of runway and chosen latest-minus-one, enforced by a CI gate at provisioning time plus a weekly cost and drift audit."
---

# ADR-0054 — FinOps guardrails: managed-service version lifecycle + periodic cost audit

**Delivery note (updated 2026-06-30):**
- **Phase 1 (EKS version lifecycle)** — ✅ Shipped: N-1 + 6-month runway selection policy live; CI gate (`check-version-lifecycle.py`) and weekly audit automation in place.
- **Phase 2 (cost breadth)** — ⬜ Deferred: RDS and other consumed-service equivalents not yet added to `rules.yaml`; tracked separately.

**Delivery note (updated 2026-07-29) — Phase 2 closed as a no-op by architecture:**
An inventory of every `aws_*` resource in `openbank-infra/aws/` shows the platform consumes
**exactly one versioned managed service: EKS** — already covered by Phase 1. There is no RDS,
ElastiCache, OpenSearch, MSK, MQ, or DocumentDB anywhere in the estate; stateful dependencies
run in-cluster per ADR-0027 (CNPG, Valkey, Redpanda), so there is no second managed-service
lifecycle to gate. EKS **addons** (vpc-cni, kube-proxy, coredns, pod-identity, ebs-csi) resolve
via `data.aws_eks_addon_version` against the cluster version and track the AWS-recommended
default on every apply — they carry no independent extended-support exposure. Phase 2's premise
("RDS and other consumed-service equivalents") names services this architecture deliberately
does not consume; if one is ever introduced, the gate extends to it at that point (the
`check-version-lifecycle.py` table+pattern is reusable verbatim).

## Context

On 2026-05-30 the sandbox EKS cluster `openbank-sandbox` was provisioned
(PR #84, ADR-0027) pinned to Kubernetes **1.31**. At that date 1.31 had
**already been in EKS extended support since 2025-11-26** — i.e. the cluster was
born ~6 months into the paid extended-support window, billed at **$0.60/cluster-hr
instead of $0.10** (a ~$365/mo, ~$4,380/yr surcharge), with only ~6 months of
(extended) life left before AWS would force-upgrade the control plane. Versions
1.33/1.34/1.35 were all in standard support and available at provisioning time.

The miss was caught only because AWS emailed an end-of-extended-support notice and
we happened to act on it; without that, the surcharge would have run indefinitely.
This is a FinOps own-goal with two root causes, neither of which any existing gate
covered:

1. **No version-lifecycle check at provisioning.** Nothing stopped a tofu root from
   pinning a managed-service version that is already in (or near) extended support.
   The governance system (ADR-0029: derive → enforce → show) gates service
   versioning, contracts, and supply-chain, but had **no cost/lifecycle gate** on the
   versions of the *managed cloud services we consume*.
2. **No periodic cost/lifecycle audit.** Versions slide toward extended support as
   time passes even if nothing changes in the repo; spend on consumed services drifts
   with no recurring "are we paying more than we must?" check.

Forces: FinOps-by-default (sandbox runs on finite AWS Activate credits); the
governance-as-code principle (ADR-0029) that a rule is written once in `rules.yaml`
and enforced in CI; the issues-as-backlog model (ADR-0052) for the actionable tail;
the cloud-agnostic substrate (ADR-0027). We also do **not** want "always newest" —
a banking substrate values a mature minor over the freshest one.

## Decision

**We make managed-service version lifecycle and recurring cost a governed concern:
a version pinned in infra must be on standard support with runway, a CI gate enforces
this at provisioning time, and a weekly audit catches drift across consumed services.**

1. **Version-selection policy (authoritative in `rules.yaml: finops`).** Any managed
   cloud service version we pin MUST be:
   - **in standard support** (never provision or sit on an extended-support version), and
   - have **≥ 6 months of standard support remaining** at the time it is pinned, and
   - selected by **"latest minus one" (N-1)** — the newest standard-support minor that
     is *not* the absolute freshest, for maturity on a banking substrate.

   Worked example (2026-06-01): newest EKS minor is 1.35 → N-1 = **1.34** (standard
   support until 2026-12-02, ~6mo runway). 1.33 would have ~2mo runway → rejected by
   the runway rule. This is exactly the choice made for the 1.31→1.34 remediation.

2. **Creation-time CI gate (static, no cloud credentials).** A check
   (`openbank-infra/scripts/check-version-lifecycle.py`) parses the pinned version
   from each tofu root listed in `rules.yaml: finops.managed_version_pins`, compares it
   against a **checked-in lifecycle table**
   (`openbank-infra/aws/finops/eks-version-lifecycle.json`), and **fails the PR** if the
   pinned version is in extended support or below the runway threshold. It also prints
   the policy-recommended target. Wired in `.github/workflows/finops-lifecycle.yml` on
   PRs touching infra. This catches the #84 mistake at review time, with zero AWS access.

3. **Lifecycle table is the one piece that needs refreshing.** The table holds AWS's
   published end-of-standard / end-of-extended dates. Until phase 2 (below) automates
   it, refreshing the dates when AWS publishes a new minor is a documented chore.

4. **Weekly periodic audit → tracking Issue (phase 1, ships now).** The same workflow
   runs on a weekly `schedule`. Because versions slide with the calendar, the static
   audit re-evaluates every pin and, on a finding (a pin now in/near extended support),
   **opens or updates a single tracking Issue** (ADR-0052 backlog; `finops` label once
   `​.github/labels.yml` lands). No cloud credentials required.

5. **Live cost audit across all consumed services (phase 2, deferred).** A follow-up
   adds a **read-only AWS OIDC role** (provisioned in tofu) so the weekly job can also
   query live signals across *every* consumed service: EKS support status,
   **AWS Budgets** threshold breaches, **Cost Anomaly Detection** findings, and
   **idle / unattached** resources (unused EIPs, unattached EBS/ENIs, idle NAT, etc.).
   Findings feed the same tracking Issue. Deferred because it introduces net-new cloud
   IAM; tracked as the actionable tail of this ADR (ADR-0052 governance-followup).

## Alternatives considered

- **"Always newest standard" selection.** Maximum runway, simplest rule. Rejected as
  the default: a banking substrate prefers a minor that has baked a couple of months
  over the freshest one. The N-1 + runway rule keeps maturity while still forbidding
  extended support. (The knob is tunable in `rules.yaml` if the posture changes.)
- **Live-only audit (query AWS, no static gate).** Rejected as the primary mechanism:
  it cannot run at PR time (no creds in PR CI), so it would not *prevent* a bad pin —
  only detect it after apply and spend. The static gate prevents; the live audit (phase
  2) is additive breadth, not the first line of defence.
- **Do nothing / rely on AWS emails.** Rejected — that is exactly what failed here.

## Consequences

**Positive**
- The #84 class of mistake (provisioning into extended support) is blocked at review,
  before any spend.
- Calendar drift toward extended support is surfaced weekly as an actionable Issue,
  not discovered via a surprise AWS email or invoice.
- Pure-static phase 1 ships immediately with no new cloud IAM or attack surface.

**Negative**
- The checked-in lifecycle table must be refreshed when AWS publishes new minors
  (a small, documented chore until phase 2 automates it).
- The N-1 + 6-month-runway rule can force an upgrade slightly sooner than strictly
  necessary; accepted as the cost of never sitting in extended support.

**Neutral**
- Phase 2 (live cost breadth) is deferred and tracked; phase 1 stands alone.
- The policy generalises beyond EKS (RDS-equivalents, runtimes) by adding entries to
  `managed_version_pins`; only EKS is wired today because it is what we consume.

## Compliance impact

- **FinOps / cost stewardship:** spend on consumed services is now a governed,
  enforced-in-CI concern rather than tribal knowledge — credits are not burned on a
  surcharge no one chose.
- **DORA (operational resilience):** never sitting in extended support keeps the
  control plane on a vendor-supported, CVE-patched version, and removes the
  end-of-extended-support **forced** auto-upgrade (an uncontrolled change) from the
  risk surface.
- **PCI/GDPR/PSD2/CNB:** unchanged — no CDE or personal data involved; the audit is
  metadata (versions, cost) only.

## References

- ADR-0027 — cloud-agnostic substrate (EKS + Karpenter); origin of the 1.31 pin.
- ADR-0029 — governance as code (derive → enforce → show); the pattern this follows.
- ADR-0052 — Issues as the actionable backlog; where audit findings land.
- `openbank-libs/governance/rules.yaml` — `finops` section (this ADR).
- `openbank-infra/aws/finops/eks-version-lifecycle.json` — checked-in lifecycle table.
- `openbank-infra/scripts/check-version-lifecycle.py` — the static gate/audit.
- `.github/workflows/finops-lifecycle.yml` — PR gate + weekly audit.
- AWS EKS Kubernetes version lifecycle & extended-support pricing (source of the table).
