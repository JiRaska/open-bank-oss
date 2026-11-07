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
