<!-- SPDX-License-Identifier: AGPL-3.0-only -->

## Read-only delivery shadow overview

`GET /api/v1/devops/delivery/overview` accepts `ROLE_ADMIN` or `ROLE_VIEWER`.
It does not start an analysis, invoke an LLM, create findings or proposals, retry
jobs, merge PRs, or deploy. Existing mutating routes remain admin-only. The
OpenAPI contract is `openbank-devops-agent/src/main/resources/openapi.yaml`.

The overview reuses the configured repository and existing GitHub credential.
It only performs GET requests to workflow-run and closed-pull-request lists;
no additional write permission is needed. The collector requests at most five
100-row pages per source (ten requests total), with short per-request timeouts
and a 15-second collection timeout. Concurrent overview requests share one
collection; its observation is cached for five minutes. `observedAt` and the
24-hour window remain those of the original collection, including on cache hits.

Interpretation:

- `mergedPrsPerDay` counts PRs whose `merged_at` falls in `[windowStart, windowEnd)`.
  A closed, unmerged PR does not count. This is PR throughput, not DORA lead time.
- Workflow groups contain runs **created** during that window, observed in their
  current state. A run created earlier and completed today is outside this cohort.
  Each run ID counts once; `runsWithReruns` reports latest attempts greater than
  one, not the full count or cost of historical attempts.
- Start-delay percentiles use `run_started_at - created_at`, with nearest-rank
  p50/p95 and an explicit sample count. They are workflow scheduling delays,
  **not** job queue time, CPU consumption, billed runner minutes or execution time.
- `CI` groups the main CI, Services CI and security workflows. `CD_AUTOMATION`
  groups auto-deploy, admin-ui deploy and deploy-window flush. Other workflows
  remain in `OTHER`; nothing outside the named families is silently discarded.
- `COMPLETE` means the bounded API pagination reached the end of the relevant
  window. `PARTIAL` means a cap, changing pagination, invalid response or later
  failed page prevented completeness. Observed counts in partial data are only
  sample counts. The exact PR/day rate is null unless PR evidence is complete.
- `UNAVAILABLE` means no usable evidence was obtained. Counts are null, not zero.
  Check credentials, repository configuration, upstream availability and API quota.
  The overview deliberately does not expose tokens, response bodies or credentials.
- A successful CD workflow is **not** proof that an application was deployed or
  is serving the intended image. `deploymentVerification` stays `NOT_COLLECTED`
  and `verifiedDeploymentsPerDay` stays null. Correlated runtime/image proof is a
  later slice; this overview must not authorize release or bypass deployment holds.

For shadow observation, read this endpoint no more frequently than every five
minutes. Persist observations in the existing operational evidence store if a
history is required; this first slice itself does not persist or schedule them.
Track source completeness alongside counts, compare complete 24-hour PR windows,
and investigate missing evidence before interpreting a zero or claiming a gain.
GitHub lists are observations, not an atomic ledger: concurrent updates may move
records between pages; detected duplicates or count disagreement mark the source
partial. Keep the first shadow sample advisory; it does not change merge policy.

API semantics: [workflow runs](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-repository)
and [pull requests](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests).

## Continuous sampling and durable history

`GET /api/v1/devops/delivery/history?limit=288` reads persisted observations only;
`ROLE_ADMIN` or `ROLE_VIEWER` is required. The limit is 1–288, newest observation
first. An empty list means no stored observations, never zero throughput.

Sampling is opt-in: `DEVOPS_DELIVERY_SHADOW_ENABLED=true` enables a five-minute
cron. The default is false and `samplingEnabled` exposes that fact even when old
history remains. `samplingCron` reports the effective configured cron;
`slotIntervalSeconds` is the independent, fixed 300-second deduplication bucket.
Enabling needs the normal reviewed image/config rollout; this PR
changes no live manifest. The sampler only uses the existing GET collector, never
analysis, proposals, retries, merges or deployments. Its liveness success is recorded
only after the database transaction completes, including a committed duplicate no-op.
A failed transaction records no success. Disabled sampling registers no liveness task.

Each row records the overview's original observation time and window, a five-minute
slot, database-write request time, collector release version and payload schema version.
The release version is not a source-SHA or deployment attestation. Cached observations
keep their original slot; replicas and retries cannot overwrite the first row for the
same repository/slot. Completeness, null counts and NOT_COLLECTED deployment status
survive a database round trip unchanged. Missing intervals remain gaps.

Rows are retained for 30 days. Each sampling transaction removes at most 1,000 expired
rows for its repository; long-disabled collectors may need several ticks to catch up.
With sampling disabled retention does not run. History reads filter the configured
repository and supported payload schema and never perform collection or cleanup.
The five-minute cadence is a sampling schedule, not proof every interval succeeded.
Compare individual complete `mergedPrsPerDay` observations against the 100 PR/day goal;
never sum or average overlapping rolling windows into daily completed-work totals.

Migration V2 is additive and does not change findings. Rollback: disable sampling and
roll back the application image, retaining the table for readback and later recovery.
Dropping `delivery_observations` is a separate destructive operator decision; it is not
part of application rollback. Old images ignore the new table. No historical success
is backfilled or inferred from an empty store.
