# CI aggregate queue baseline — 2026-10-04

This is a dated measurement for [#11442](https://github.com/JiRaska/open-bank-oss/issues/11442), before the scheduling changes in [#12080](https://github.com/JiRaska/open-bank-oss/pull/12080) merge. It is not evidence of an improvement from that PR.

## Sample and method

- GitHub Actions workflow `CI` (workflow ID `304790274`), 20 completed, non-cancelled pull-request runs from 13:31–17:01 UTC and 20 completed `main` push runs from 11:18–16:08 UTC. The PR runs are evenly spaced through the latest 33 eligible runs in two 30-run API pages; the main runs are the 20 most recent. Six PR runs failed and 14 succeeded; all 20 main runs succeeded. Every sampled workflow had nine jobs, and every job reported the `ubuntu-latest` label.
- Queue = GitHub Jobs API `started_at − created_at`; execution = `completed_at − started_at`. The aggregate predecessor delay is the aggregate job's `created_at` minus the latest completed required predecessor (`changes-ui`/UI build/cross-package guards for `Admin UI`; three gate shards and `Admin UI` for `Validate manifests`). Skipped predecessors are excluded.
- Verdict latency = `Validate manifests.completed_at − workflow_run.created_at`, including failed runs. Time-to-green uses only successful runs. The p50 is the ordinary median (average of the two middle values for 20 runs); p95 is nearest rank. The hosted runner-minute figure is an **estimate**: sum of `ceil(execution seconds / 60)` for non-skipped `ubuntu-latest` jobs in each sampled `CI` run, then take the median/p95. It is not a GitHub billing export and excludes separate Services CI workflows.
- Reproduce timestamps with `gh api 'repos/JiRaska/open-bank-oss/actions/runs/<run-id>/jobs?per_page=100'` and run creation/conclusion with `gh api 'repos/JiRaska/open-bank-oss/actions/runs/<run-id>'`. The exact run IDs are below; each resolves under `https://github.com/JiRaska/open-bank-oss/actions/runs/<run-id>`.

| Event / measure | p50 | p95 |
| --- | ---: | ---: |
| PR `Admin UI` runner queue | 1,741 s | 2,340 s |
| PR `Admin UI` execution | 3 s | 4 s |
| PR `Validate manifests` runner queue | 1,781.5 s | 2,065 s |
| PR `Validate manifests` execution | 3 s | 4 s |
| PR aggregate predecessor completion → job creation | 0 s | 1 s |
| PR workflow creation → aggregate verdict, 20 runs | 6,959 s | 8,304 s |
| PR workflow creation → green, 14 successful runs | 7,036.5 s | 11,111 s |
| PR estimated hosted runner minutes, `CI` only | 12 min | 13 min |
| `main` `Admin UI` runner queue | 1,745.5 s | 2,613 s |
| `main` `Admin UI` execution | 3 s | 4 s |
| `main` `Validate manifests` runner queue | 1,802.5 s | 2,266 s |
| `main` `Validate manifests` execution | 3 s | 4 s |
| `main` aggregate predecessor completion → job creation | 0 s | 1 s |
| `main` workflow creation → green, 20 runs | 7,995 s | 8,830 s |
| `main` estimated hosted runner minutes, `CI` only | 24 min | 25 min |

The two aggregate jobs are serial in `ci.yml`: `Validate manifests` needs `Admin UI`, while each waits for a fresh `ubuntu-latest` runner. The observed predecessor-to-creation delay is at most one second at p95, and both aggregate executions take only seconds. This identifies two queue waits on the critical path. It does **not** identify whether repository concurrency, account capacity, GitHub-hosted capacity, or another scheduler constraint caused those waits.

Fan-out differs: `Admin UI build` was skipped in 19/20 PR runs and succeeded in 20/20 main runs. Compare post-change PRs to similarly scoped PRs and main pushes separately; do not attribute the difference between these two samples to event type. After #12080 merges, repeat at least 20 runs per event, compare p95 PR time-to-green and estimated hosted runner minutes under similar fan-out, and revert the scheduling change if the holistic result worsens. A proposed Services CI `max-parallel` increase still needs its own comparison.

### Pull-request run IDs

```text
37218948855 37217361620 37217353234 37217336105 37214870231
37213716369 37213612839 37211656533 37211072590 37210897015
37208386932 37208156961 37208146398 37208040982 37207309809
37207187156 37207030709 37206759683 37206322921 37205913741
```

### Main-push run IDs

```text
37215668586 37215148111 37215081178 37214926591 37214677481
37214660291 37212688462 37212182320 37212164196 37210694058
37207413714 37206217987 37204062010 37203920900 37203300488
37202430001 37202219383 37201411471 37200102243 37198303689
```
