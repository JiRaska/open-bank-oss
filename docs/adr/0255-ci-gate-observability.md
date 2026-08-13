---
date: 2026-08-09
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance, observability]
summary: "Gate-run outcomes, collected like DORA (ADR-0061): a CI-time collector bakes a read-only snapshot, no live token in the pod. Tier 2 (ClickHouse + Grafana) is committed and verified against a real ClickHouse engine; the live cluster is not."
followup: "#4439 — verify ArgoCD sync, the PostSync schema hook, the CronJob's egress, and the Grafana dashboard render against the real cluster (this session has no kubectl/cluster access)"
---

# ADR-0255 — CI/QG observability: a read-only health snapshot, an admin-ui panel, and a ClickHouse/Grafana historical trend

**Delivery note (2026-08-10):** this ADR's own text described Tier 2 (the ClickHouse table, the
puller CronJob, the Grafana dashboard) as "delivered as code in this change" — it was not.
Re-auditing the tree on 2026-08-10 found none of the three files it names anywhere in the repo.
That gap is closed by this note's commit: all three exist now, plus a schema-apply Job and an
ExternalSecret the original text implied but did not enumerate as separate files. Two real bugs
were found and fixed by actually running the DDL and an INSERT against a real
`clickhouse/clickhouse-server:24.8` container (Docker, not assumed from reading ClickHouse docs):

1. The HTTP interface rejects a multi-statement script by default
   (`Multi-statements are not allowed`) — the schema-apply Job now POSTs the `CREATE DATABASE`
   and `CREATE TABLE` statements as two separate requests, not one script body.
2. `DateTime64` via `JSONEachRow` rejects GitHub's `...Z`-suffixed ISO 8601 timestamps outright
   (`CANNOT_PARSE_INPUT_ASSERTION_FAILED`) and wants a space, not `T`, between date and time —
   the puller now converts before inserting (`clickhouse_datetime()` in `pull.py`).

Every dashboard panel's `rawSql` was run against that same container, seeded with 161 real gate
rows pulled from a live `ci.yml` run via `gh api` (not synthetic fixtures) — including a
falsification of the "gates currently red" panel: empty against all-passing real data, and
correctly populated after inserting one synthetic `status: failed` row. One panel's ClickHouse
query had a self-shadowing column alias (`argMax(budget_seconds, ...) AS budget_seconds`) that
produced `ILLEGAL_AGGREGATION` — caught by the same real-engine run, fixed by renaming the alias.

**What is still honestly unverified**, unchanged from the original text below: ArgoCD actually
syncing these manifests, the PostSync hook firing (it will, on this same PR's diff to the schema
ConfigMap — the shape that DOES trigger a sync, per the `temporal-namespace-registration.yaml`
precedent this Job's hook annotations copy), the CronJob's egress reaching `api.github.com`
under the real VPC CNI enforcement, and the dashboard rendering in the live Grafana. Real
ClickHouse behaviour is now proven; real cluster behaviour is not, and this note does not
pretend otherwise.

One design point worth surfacing to a reviewer explicitly, not buried in a file comment: the
puller's NetworkPolicy is the **second** direct-internet-egress hole in the entire fleet. Today
there is exactly one (`ai-platform/networkpolicy-litellm-egress.yaml`), and `agent-service`'s own
egress policy states outright that every other workload is expected to route through it rather
than get its own hole. The GitHub REST API cannot route through an LLM gateway, and Kubernetes
NetworkPolicy has no FQDN selector (only CIDR/IP), so the alternatives were: pin GitHub's
published IP ranges (rejected — they rotate, and this fleet has no automation to keep a pinned
list current, which trades a security question for a silent-outage one), or accept a
`0.0.0.0/0:443` rule scoped by pod selector to only this CronJob's pods. Chose the latter.
See `networkpolicy-gate-health-puller-egress.yaml`'s own header for the full reasoning — this is
flagged here because it is the one part of Tier 2 that changes the fleet's security posture
rather than adding a self-contained new pipeline, and a decision like that should be visible in
the document a reviewer reads first, not only in the file it lives in.

## Context

#4339 closed five structural gaps in the gate estate and ADR-0254 adds three more
checks on top of it. None of that is visible anywhere: `gates.yaml` is machine-
enforced but not machine-*observed* — nobody can currently see, in one place,
which gates exist, how long each one has taken over the last month, which ones
have gone red on a real PR versus never once, or which advisory findings are
overdue for the re-verification ADR-0254 now requires.

Two dashboards exist today and neither answers this:

- `dashboard-openbank-ci-finops.yaml` is titled "CI/CD FinOps" but every panel is
  about the **runner fleet** — ARC pods, Karpenter node count, CPU/memory, a NAT
  cost tip. Nothing in it is about a gate.
- The admin-ui DevOps page (`openbank-admin-ui/src/app/devops/page.tsx`) shows DORA
  metrics, sourced by `scripts/collect-dora.mjs` writing `dora.json` at build time
  (ADR-0061). That ADR's decision — "derive from the bank's own sources in CI,
  serve a read-only snapshot, never a live privileged token in the admin-ui pod" —
  is exactly the shape needed here, and exists already as an accepted, shipped
  pattern to build on rather than re-litigate.

There is no equivalent for the gate estate: how many of the 135 gates are enforced
vs. advisory, how many have a self-test or a subject floor — numbers this
session's own PRs printed to a CI log and nowhere else — and whether any of that
is trending in the right direction.

## Decision

**Three tiers, in decreasing order of how much of each is delivered and verified
in this change.**

### Tier 1 — read-only snapshot, admin-ui panel. Built and verified now.

Mirrors ADR-0061's DORA pattern exactly, because it is the same problem (CI-time
truth, served read-only, no live token in the pod) with a different metric:

1. `run-gates.py` gains `--json <path>`: writes one record per gate
   (`id, group, mode, status, seconds, subjects, selftest_declared,
   selftest_status, budget_seconds`) after a run completes. Pure addition —
   existing callers (`ci.yml`'s `gates` job) are unaffected until they opt in.
2. The `gates` job in `ci.yml` writes that file and uploads it as a workflow
   artifact per shard (`actions/upload-artifact`), named `gate-results-<group>`.
   An artifact, not a log line — CLAUDE.md documents at length why parsing a job's
   own printed log back out is fragile (the log contains the step's own `run:`
   script text, several documented false positives/negatives came from exactly
   that), and the Actions Artifacts API gives typed, structured JSON with no
   parsing risk.
3. `openbank-admin-ui/scripts/collect-gate-health.mjs` — new, same shape as
   `collect-dora.mjs`: runs inside `admin-ui-deploy.yml`'s build job (which
   already checks out full history for the DORA collector), calls the GitHub
   Actions REST API for the last N `ci.yml` runs, downloads each shard's
   `gate-results-*` artifact, and aggregates into `gate-health.json`: per-gate
   pass streak, last-red run (SHA + PR + timestamp), a `flaky` flag (both PASS and
   FAIL seen across the last 10 runs on distinct SHAs), wall time percentiles per
   shard, and the estate-wide counts (enforced/advisory, self-test/no,
   floor/no, budget/no) that #4339's PRs currently only print once, to a log
   nobody keeps.
4. This needs **no new permission grant at all**, which was not obvious until
   building it: `actions/upload-artifact` requires no special permission to
   upload an artifact for the run it is already part of, and
   `admin-ui-deploy.yml`'s `build-push` job already carries `actions: read`
   (granted for `gh run download`'s SBOM-staging step) — the exact scope the
   collector needs to call the Actions API for `ci.yml`'s run/job/artifact data.
   The boundary ADR-0061 drew (no privileged token in the admin-ui pod) is
   preserved exactly: the token lives for the duration of one CI job, is scoped
   by GitHub to that job, and is gone before the image it built ever runs.
5. `src/app/api/devops/gate-health/route.ts` + a QG section on the DevOps page —
   same `DataUnavailable`-on-missing-snapshot pattern as the DORA route, so a
   collector failure degrades to an honest empty state, never a fabricated number.

This tier needs no new infrastructure, no new secret, and no cluster access to
verify — the collector's GitHub API calls were run for real against this repo's
actual run history while building this change (see the PR for the resulting
JSON), which is the same falsification standard #4339's gates hold themselves to.

### Tier 2 — ClickHouse historical trend + Grafana dashboard. Designed and coded, NOT deployed or cluster-verified from this session.

A build-time snapshot answers "what is true now" and a shallow rolling window; it
cannot answer "how has `gates (gitops)`'s wall time trended over the last
quarter" because nothing keeps that history past the current admin-ui build.
Prometheus cannot either — its retention is 12h with no long-term store (recorded
in this repo's own reference notes). ClickHouse is the fleet's existing answer to
exactly this shape of problem: `openbank_analytics.bronze_events` already holds
domain events fed by `analytics-sink`, and `dashboard-openbank-business-warehouse.yaml`
already queries it from Grafana over the `grafana-clickhouse-datasource` plugin
(datasource uid `clickhouse`) — a **proven**, already-wired pairing, not a
speculative one.

Delivered as code in this change:

- `openbank-infra/gitops/components/analytics/ci-gate-runs-schema.yaml` (or the
  fleet's existing migration mechanism for `openbank_analytics`) — a new table
  `openbank_analytics.ci_gate_runs` (one row per gate per CI run: timestamp, sha,
  pr number, event type, gate id, shard, mode, status, seconds, subjects,
  selftest fields, budget fields).
- `openbank-infra/gitops/components/analytics/cronjob-gate-health-puller.yaml` —
  an in-cluster CronJob, same class as the namespace's existing
  `cronjob-rum-attribute-audit.yaml`, that pulls recent `ci.yml` run artifacts via
  the GitHub REST API (**egress only** — outbound HTTPS to `api.github.com`, no
  new inbound surface at all) using a read-only fine-grained PAT scoped to
  `Actions: read` on this one repository, stored via the fleet's ExternalSecret/
  OpenBao convention, and writes rows into `ci_gate_runs` over the existing
  internal ClickHouse endpoint.
- A NetworkPolicy scoped to that CronJob's pod: egress allowed to
  `api.github.com` and to the ClickHouse Service, nothing else — the same
  allow-list shape `gen-network-policies.py` already derives for every other
  workload in this namespace.
- `openbank-infra/gitops/components/observability/dashboard-openbank-ci-gates.yaml`
  — a new Grafana dashboard, same structure as `dashboard-openbank-ci-finops.yaml`,
  querying `ci_gate_runs` via the `clickhouse` datasource: wall time per shard
  over time, pass-rate per gate, last-red timestamp per gate, and an alert rule
  (Grafana alerting, not a new PagerDuty-class dependency) on a shard's rolling
  p95 wall time crossing its declared `budget_seconds` sum.

**What is honestly unverified**: this session has no `kubectl` access and no
Grafana session — the manifests and the puller script are written and unit-
testable in isolation (the puller's GitHub-API-parsing and SQL-generation logic),
but ArgoCD syncing them, the CronJob actually running against real cluster
network policy, and the dashboard actually rendering in the live Grafana are not
things I can confirm from here. Stating that plainly is the point of this
section, not a hedge to bury — CLAUDE.md's own rule is "report outcomes
faithfully… when something is done and verified, state it plainly; when a step
was skipped, say that." A human (or a session with cluster access) needs to
confirm the sync and take one screenshot before this tier is called done.

### Tier 3 — derived metrics. No new infrastructure; queries over Tier 1/2 data.

Once gate-run history exists (Tier 1's rolling window is enough for these; Tier 2
extends the same queries over a longer horizon):

- **Time-to-signal**: first-gate-red timestamp minus the triggering push
  timestamp, per PR. Not "CI took N minutes" but "how long before the author's
  first red" — the number a developer actually feels, and one nothing today
  measures.
- **Flaky-gate detection**: a gate that shows both PASS and FAIL across the last
  10 runs on *distinct, unrelated* SHAs (not the ordinary red-then-fixed pattern
  on the *same* PR) is flagged. Computed in the Tier 1 collector already — no
  separate infrastructure, just a field in `gate-health.json`.
- **`incident-gate-coverage`** (ADR-0254) surfaces here as a single ratio on the
  DevOps page and the Grafana dashboard, rather than living only in a CI log.

## Alternatives considered

- **Push gate results directly from the hosted GitHub Actions runner into
  ClickHouse.** Rejected. `openbank-infra/gitops/components/analytics/network-policies.yaml`
  and `clickhouse-grafana-network-policy.yaml` are **ingress allow-lists scoped to
  in-cluster namespaces** — ClickHouse has no Ingress of its own at all. Making it
  reachable from a `ubuntu-latest` hosted runner means adding a brand-new
  internet-facing write path into internal analytics infrastructure. That is a
  real security decision — new inbound exposure to a service that today has none —
  and this repo's own rule is that money-path and security-relevant changes get a
  threat-model and two-approval review (ADR-0030); a CI dashboard is not worth
  being the thing that opens that door. The in-cluster CronJob (egress-only,
  Tier 2) gets the same data in with the opposite, much smaller, risk shape.

- **Have the admin-ui runtime pod call the GitHub API live for the QG page.**
  Rejected outright — this is the identical mistake ADR-0061 already made and
  reversed for the DORA route (a privileged `GITHUB_TOKEN` living in a pod that
  should hold none). Re-making it here would undo that decision by a side door.

- **Skip Grafana/ClickHouse entirely; ship only the admin-ui snapshot.** The
  strongest alternative, seriously considered, and the one with the best
  effort-to-value ratio — genuinely the single highest-value piece of this whole
  ADR, which is why it is Tier 1 and built first. Not chosen as the *whole*
  answer because it structurally cannot do two things asked for directly:
  alerting when a shard's wall time regresses (a build-time snapshot has no
  standing process to page anyone), and one-pane correlation with the fleet's
  other Grafana dashboards (deploys, node capacity, incidents). Kept as Tier 2,
  explicitly lower-confidence per the verification note above.

- **A Grafana "Infinity"/JSON-API datasource reading `gate-health.json` straight
  from the deployed admin-ui, skipping ClickHouse entirely.** Considered — a
  smaller build than a new table plus a CronJob. Rejected for this iteration
  because this repo's Grafana is a self-hosted `kube-prometheus-stack` release
  (confirmed via `openbank-infra/scripts/grafana-local.sh`) whose Helm values —
  and therefore its installed plugin list — are not visible anywhere in this
  repo's tracked tree, so "is the JSON/Infinity datasource plugin even
  installed" cannot be answered without cluster access either. The ClickHouse
  path builds on a datasource **proven** wired today
  (`dashboard-openbank-business-warehouse.yaml`'s `grafana-clickhouse-datasource`,
  uid `clickhouse`); building on confirmed infrastructure beats gambling on an
  unconfirmed plugin. If a later session confirms the JSON datasource is already
  installed, it is a legitimate lighter-weight replacement for Tier 2 and this
  ADR should be revisited, not silently overridden.

- **Retroactively backfill `ci_gate_runs` from all historical CI runs so the
  Grafana trend has data from day one instead of starting empty.** Rejected for
  the same reason ADR-0254 rejects retroactive log-mining: it requires parsing
  historical logs from before the structured-artifact format existed (this
  change), which is the fragile territory this repo's CI section warns about
  repeatedly. The dashboard starts thin and fills in from the day it deploys —
  honest and consistent with how `dora.json` and every other CI-derived snapshot
  in this codebase already behaves (no fabricated history).

## Consequences

**Positive**
- The estate becomes observable without granting the admin-ui pod, or any pod
  outside a single scoped CI job, a live GitHub token — the exact boundary
  ADR-0061 already established, extended rather than re-litigated.
- `gate-health.json` gives every future audit (the next #4339) a starting dataset
  instead of a fresh worktree-and-grep exercise like this one was.
- The Grafana path, once verified, reuses infrastructure and a datasource pairing
  this fleet already runs in production — no new plugin, no new class of
  dependency.

**Negative**
- Tier 2 introduces one new CronJob, one new NetworkPolicy, one new ClickHouse
  table, and one new PAT-backed ExternalSecret. Each is a real, if small, addition
  to the attack surface and the operational estate, and none of it is verified
  against the live cluster by this change — see the followup.
- The collector's rolling-window design (Tier 1) means `gate-health.json` is only
  as deep as the artifact retention window GitHub keeps (default 90 days unless
  configured otherwise) until Tier 2's ClickHouse table is actually receiving
  data; until then, "trend over a quarter" is aspirational.
- `incident-gate-coverage`'s extraction of "which CLAUDE.md bullets look
  gate-worthy" (ADR-0254) is a heuristic over prose, and heuristics over prose in
  this exact codebase have a documented failure mode (matching a comment
  *about* the thing rather than the thing). It ships advisory precisely so a bad
  heuristic match embarrasses nobody by blocking a PR.

**Neutral**
- No money-path service is touched. The new CronJob and table sit in the existing
  `analytics` namespace alongside the RUM/business-warehouse pipeline they mirror
  in shape.

## Compliance impact

- PCI DSS: not applicable — CI/engineering observability tooling, no cardholder
  data path, no new payment-adjacent workload.
- DORA: not applicable — internal engineering practice; this ADR does not cite a
  specific DORA ICT-risk-management article and should not be read as claiming
  regulatory coverage.
- GDPR: not applicable — no personal data is collected (gate ids, timings, PR
  numbers and commit SHAs are not personal data).
- PSD2: not applicable.
- CNB: not applicable.

## References

- ADR-0061 — DORA metrics from in-house sources (the precedent this ADR's Tier 1
  extends verbatim: CI-time collector, baked snapshot, no live token in the pod).
- ADR-0254 — CI gate estate integrity (sibling; `incident-gate-coverage` surfaces
  through this ADR's snapshot and dashboard).
- `reference-prometheus-retention-is-12h-no-long-term-store` (session memory) —
  why Prometheus cannot be the historical store for this.
- `openbank-infra/gitops/components/observability/dashboard-openbank-ci-finops.yaml`
  — the existing "CI" dashboard this ADR's Tier 2 dashboard sits beside, not
  inside (it is about runner infra, not gates).
- `openbank-infra/gitops/components/observability/dashboard-openbank-business-warehouse.yaml`
  — the proof that `grafana-clickhouse-datasource` (uid `clickhouse`) is already
  wired and queried in this cluster.
- #4339 and its four PRs — the audit this observability layer makes durable.
