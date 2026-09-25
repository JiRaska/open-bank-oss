---
date: 2026-09-25
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [finops, observability, analytics]
summary: "A daily in-cluster CronJob prices the OpenTofu envs on main with infracost, evaluates versioned FinOps rules, adds Cost Explorer actuals and writes ClickHouse; Grafana shows compliance, savings and estimate vs actual."
followup: "#10856 — seed the Infracost key in OpenBao, observe the first live run, and confirm the dashboard renders against real data (no cluster access from the authoring session)"
---

# ADR-0316 — Cloud FinOps dashboard from in-cluster Infracost + Cost Explorer

## Context

ADR-0054 decided the periodic cost audit and shipped the `admin-ui/cost-collector` CronJob, which
snapshots 30 days of Cost Explorer into a ConfigMap for the admin-ui FinOps panel. ADR-0062 decided
how spend is allocated to services (requests-weighted showback). ADR-0112 decided a finops-agent
that detects cost *precursors* at runtime (NAT egress, cross-AZ traffic, node churn). ADR-0255
established ClickHouse + Grafana as this fleet's answer to "trend over more than Prometheus' 12h
retention". None of them covers **what the infrastructure code itself will cost and whether it
follows FinOps practice**: the only IaC cost signal today is the `infracost` PR comment in
`platform-tofu.yml` / `substrate-tofu.yml`, which prices one diff at a time, keeps no history,
and evaluates no rules. The cost-collector snapshot has no IaC side and is a ConfigMap, so it
cannot carry history or be joined with anything.

The target is the Infracost Cloud dashboard — FinOps best-practice compliance %, issues detected,
potential and realised savings, all as a trend — plus the one view that product cannot give from
IaC alone: the estimate next to the actual AWS bill.

## Decision

We will run a daily in-cluster CronJob, `admin-ui/cloud-finops-collector`, next to
`cost-collector.yaml` and reusing its ServiceAccount (and so its existing read-only
`ce:GetCostAndUsage` Pod Identity role — no new AWS privilege). Each run:

1. installs a pinned infracost release binary, verified by sha256 (the official infracost images
   are amd64-only, every node here is arm64);
2. sparse-clones `main` of this public repository anonymously over https;
3. runs `infracost breakdown --format json` over every env listed in
   `openbank-infra/aws/finops/cloud-finops-rules.json`;
4. evaluates the FinOps rules in that same versioned file (gp2 to gp3, previous-generation
   instance families, x86 families with a Graviton equivalent, missing required cost-allocation
   tags, more than one NAT Gateway per project) with `openbank-infra/scripts/cloud-finops-collect.py`
   **executed from the clone**, so the code that runs is the code the unit tests cover;
5. pulls 35 days of Cost Explorer DAILY x SERVICE;
6. inserts into four ClickHouse tables (`finops_iac_resources`, `finops_findings`,
   `finops_aws_daily_cost`, `finops_ingest_runs`), delivered through the existing PostSync
   schema-apply hook, and reads every insert back.

`finops_ingest_runs` is written last, only after every other insert read back correctly, and is
the dashboard's freshness signal. An empty infracost breakdown, an empty Cost Explorer response,
or a read-back mismatch exits non-zero — none of them may be recorded as $0.

The Grafana dashboard `dashboard-openbank-cloud-finops.yaml` reads only these tables.

## Alternatives considered

- **A scheduled GitHub Actions job** writing to ClickHouse. The infracost key already lives in
  Actions, but ClickHouse has no public ingress, so CI would need a network path and a write
  credential into the warehouse. Rejected by the owner in favour of the in-cluster path, which
  already has both the warehouse and the Cost Explorer role.
- **Infracost Cloud itself** (upload runs, use its dashboard). Gives the compliance and savings
  views for free, but moves the data to a third party, cannot show the actual AWS bill next to the
  estimate, and would leave the fleet's cost view outside the Grafana/ClickHouse estate every
  other trend lives in. Rejected.
- **Extending the cost-collector ConfigMap.** No history, no join with the IaC side, 1 MiB limit.
  Rejected; the cost-collector stays as the admin-ui panel's feed.

## Consequences

**Positive**
- One place shows IaC estimate, FinOps compliance, savings (potential and realised) and actual
  spend over time.
- Rules are data in a versioned file; every finding row carries `rules_version`, so a rule change
  is distinguishable from an estate change in the trend.

**Negative**
- A second internet-reaching workload in `admin-ui` (github.com for the clone and the release
  binary, the infracost pricing API). The namespace has no egress policy today, so nothing new is
  opened, but nothing narrows it either.
- The infracost binary is fetched at run time; the pinned sha256 is the integrity control, and
  bumping the version is a manual edit of two env values. It pins the v0.10 line; infracost has
  since published a v2 CLI, and migrating is a separate change.
- Estimates cover what infracost can price from HCL; usage-based charges are priced at zero
  without a usage file, so the estimate is a floor and the estimate-vs-actual gap is expected to
  be positive.

**Neutral**
- Categories used for estimate vs actual are deliberately coarse (IaC resource types and Cost
  Explorer services do not map one-to-one).

**Enforcement.** No new gate. The evaluator's known-positive / known-negative suite
(`openbank-infra/scripts/cloud_finops_collect_test.py`) runs in the existing `*_test.py`
discovery gate over `openbank-infra/scripts`, and it fails if a rule in the rules file has no
fixture. At runtime the control is the freshness tile (red above 36h) plus `KubeJobFailed`.

### Delivery check

- `python3 -m unittest openbank-infra/scripts/cloud_finops_collect_test.py` passes.
- Live: `SELECT dateDiff('hour', max(run_ts), now()) FROM openbank_analytics.finops_ingest_runs`
  returns a value below 36. Until the Infracost key is seeded in OpenBao this returns decades
  (no row at all) — that is the not-delivered state, and it is why the status is `partial`.

## Compliance impact

- PCI DSS: not applicable — infrastructure cost metadata only, no cardholder data.
- DORA:    not applicable — cost reporting, not an ICT risk or resilience control.
- GDPR:    not applicable — no personal data is collected.
- PSD2:    not applicable — no payment-service surface.
- CNB:     not applicable — no regulatory reporting output.

## References

- ADR-0054, ADR-0058, ADR-0062, ADR-0112, ADR-0255
- `openbank-infra/gitops/components/admin-ui/cloud-finops-collector.yaml`
- `openbank-infra/gitops/components/analytics/cloud-finops-schema-configmap.yaml`
- `openbank-infra/gitops/components/observability/dashboard-openbank-cloud-finops.yaml`
- `openbank-infra/aws/finops/cloud-finops-rules.json`
