---
date: 2026-06-03
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, ci, analytics]
summary: "DORA metrics are derived from the bank's own authoritative sources by a CI collector and served as a read-only snapshot, so no privileged GitHub or Prometheus token ever lives in the admin-ui pod."
---

# ADR-0061 — DORA metrics from in-house sources, derived + snapshotted

**Delivery note (updated 2026-06-30):**
- **Phase 1 (Deployment Frequency)** — ✅ Shipped: `scripts/collect-dora.mjs` derives Deployment Frequency from `git log --first-parent`; `dora.json` baked and served read-only.
- **Lead Time / CFR / MTTR** — ⬜ Pending: Phase 2 (Lead Time via deploy-event log), Phase 3 (MTTR from ICT incident register), and CFR (reverts/incidents) not yet implemented; require deploy-event and incident data sources.

## Context

The DevOps page shows the four DORA metrics (Deployment Frequency, Lead Time for
Changes, Change Failure Rate, Mean Time to Restore) but all four were empty, each
captioned "Requires GITHUB_TOKEN / Prometheus / PagerDuty." The route
(`api/devops/dora`) tried to compute them by calling the **GitHub API live** (with
a `GITHUB_TOKEN`) and **Prometheus** directly from the admin-ui pod.

That is the wrong layer, on three counts:

1. **It breaks the read-only-consumer rule.** The admin-ui must not hold privileged
   runtime credentials (no `GITHUB_TOKEN`, no live billing/GitHub access) — exactly
   why cost and test metrics are CI-derived snapshots served read-only
   (`cost-report.json`, `test-results.json`, ADR-0029 rule #3, ADR-0054).
2. **It doesn't work here** — the pod has no token and no Prometheus reachability,
   so every card is blank.
3. **The proxies are conceptually wrong.** "Closed PRs" ≠ deployments;
   "commit → merge" ≠ lead time to *prod*; a "30-day 5xx rate" ≠ change failure
   rate; and MTTR was punted to a SaaS (PagerDuty) the bank doesn't need.

## Decision

**Derive DORA from the bank's own authoritative sources, computed in CI / a
collector, and serve a read-only snapshot — never a live privileged token in the
admin-ui pod.** Same pattern as cost/test snapshots: a collector writes a JSON,
the build bakes it (and/or a CronJob refreshes a ConfigMap), the route serves it,
honest `available:false` when a source is absent. Each metric is sourced from the
*right* authoritative event, not a convenient proxy:

| Metric | Authoritative source |
|--------|----------------------|
| **Deployment Frequency** | trunk ship events = first-parent (squash) merges to `main` → the deploy pipeline (ADR-0053/0060). Git-derivable, no token. |
| **Lead Time for Changes** | commit-authored → deployed. A squash-merged trunk flattens author==merge date, so this needs **deploy-event/commit correlation** (the deploy pipeline emitting `commit → deploy-time` records), not git alone. |
| **Change Failure Rate** | deployments correlated with **reverts / rollbacks / hotfixes / incidents**. The Prometheus 5xx rate is a *secondary* operational signal, not CFR. |
| **MTTR** | the in-house **ICT incident register** (`openbank-security-scanner` `IctIncidentResource`) — DORA Art. 17 mandates it anyway. Alertmanager may auto-open incidents into it; the register is the source of truth, not PagerDuty. |

### Phasing

1. **Phase 1 (this increment): Deployment Frequency** — `scripts/collect-dora.mjs`
   derives it from `git log --first-parent` over a 30-day window and bakes
   `dora.json`; the route serves it (no token). Lead Time is computed too but
   reported `null` on a squash-merged trunk (honest, with a reason), pending
   Phase 2.
2. **Phase 2: Lead Time + Change Failure Rate** — from a deploy-event record
   (commit SHA → deploy timestamp, emitted by the ADR-0060 apply pipeline) and
   revert/incident correlation.
3. **Phase 3: MTTR** — from the ICT incident register.

## Alternatives considered

- **Live GitHub/Prometheus/PagerDuty from the pod** (status quo). Rejected: breaks
  the read-only-consumer rule, needs runtime secrets, and didn't work.
- **Keep the 5xx "CFR proxy" as the headline CFR.** Demoted to a secondary signal;
  the headline CFR is change-tracked (reverts/incidents).
- **Adopt PagerDuty for MTTR.** Rejected: the bank must keep an ICT incident
  register for DORA regardless; reuse it — fewer moving parts, audit-aligned.

## Consequences

**Positive**
- DORA cards populate from data the bank already owns; no runtime tokens; works in
  sandbox and prod identically (read-only-consumer, ADR-0029 derive→show).
- MTTR becomes DORA-compliant by construction (sourced from the mandated register).

**Negative**
- Phase 1 fills one card (Deployment Frequency); Lead Time/CFR/MTTR land in later
  phases. The page states each honestly rather than showing a fabricated number.
- A squash-merged trunk means Lead Time needs a deploy-event log (Phase 2 work).

## References

- ADR-0029 — derive-from-code / show-in-UI; read-only consumer (rule #3).
- ADR-0053 / ADR-0060 — the deploy pipeline = the authoritative deployment event.
- ADR-0054 — cost snapshot pattern this mirrors.
- `openbank-admin-ui/scripts/collect-dora.mjs`, `src/app/api/devops/dora/route.ts`.
- DORA / EU 2022/2554 Art. 17 — ICT incident register (the MTTR source).
