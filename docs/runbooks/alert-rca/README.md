# Critical alert RCA review (ADR-0241 D3)

`ledger.jsonl` is an append-only record of `critical` alert firings, one JSON
object per firing, maintained by `.github/scripts/alert-rca-ledger.py` and
published weekly by `.github/workflows/alert-rca-weekly.yml`. `YYYY-MM.md` is
the rendered weekly review; the `RCA` column is filled in by hand at the Monday
10:00 UTC review.

Both files are **derived** — regenerate them, never hand-edit, except for the
RCA column of the monthly review.

## Why there is no MTTR figure here

ADR-0241 D1 targets critical-alert **P90 ≤ 4 h**. No source reachable from a
GitHub Actions workflow can currently date an alert resolution finely enough to
support that number:

| Source | Holds resolved-alert history? | Usable for a 4 h P90? |
|---|---|---|
| Alertmanager `/api/v2/alerts` | No — returns **active** alerts; it is not a history API | No. A resolution is only ever inferred from an alert's absence at the next poll, i.e. ±24 h at the daily digest cadence |
| Prometheus `ALERTS` | 12 h retention, no long-term store (ADR-0027) | No. It cannot even span the 7-day review window, and a longer range query truncates silently rather than erroring |
| Loki | 168 h retention | No. It holds notification logs, not an authoritative open/close record |
| GoAlert Postgres | **Yes** — durable open/close history with real timestamps | Yes in principle, but it is cluster-internal with no Ingress (ADR-0056/ADR-0088) and no workflow-reachable credential exists |

So `alert-rca-ledger.py` **refuses** to emit a P90 whose timestamp granularity
is coarser than half the target, and reports the reason instead. The refusal is
conditional, not cosmetic: the same code path emits a real P90 as soon as
records arrive carrying a fine `granularitySeconds`, and the script's
`--self-test` proves both directions.

Unblocking the MTTR half of ADR-0241 means giving the review a durable
alert open/close feed — a GoAlert history export, or an Alertmanager webhook
receiver persisting `firing`/`resolved` transitions. It is **not** a matter of
polling Alertmanager more often. Tracked by #5869.

## Recurrence guard

ADR-0241 D3: an alert name that fires **3 or more times in 14 days** is treated
as a reliability defect and gets a follow-up issue opened automatically before
the next review. Counted per `alertname` (not per service), over `startsAt`,
counting resolved and still-open firings alike.

## Classifying recurring alerts

Record the alert instance, time window, environment and source evidence in the
weekly RCA. Classify it before changing thresholds or routing:

| Outcome | Evidence to record | Follow-up |
|---|---|---|
| Confirmed fault | The condition in the rule occurred. Record serving or business impact separately, including when impact is unknown. | Assign remediation or recovery to the affected service owner. |
| False positive | The condition the alert claims to detect was absent. Record the authoritative signal showing that it was absent. | Fix the rule, input signal or alert text at the layer that made the wrong claim. |
| Evidence unavailable | The required signal, query or observation was missing. | Restore or verify the evidence before grading the alert. |
| Repeat notification | Multiple messages represent the same firing and operational action. | Review grouping and repeat timing with the owner. |

Flapping identifies which alerts to review. Before changing a rule for a known
false positive, add a case to the existing promtool suite using the shipped rule.
Cover the reported false-positive input and a genuine failure the rule must
still detect. Use existing observations to check the outcome after deployment.

## Observation coverage

The in-cluster standing-critical digest writes one S3 observation envelope per
successful UTC daily poll, including when its `alerts` array is empty. The same
envelope carries Watchdog and AlertRuleFlapping for the existing weekly
alert-noise report. The noise report requires the current UTC day's envelope
and a Watchdog in that observation.

The RCA review requires a valid envelope for each of the last seven expected
daily polls at 08:30 UTC. Duplicate observations of the same day count once.
An incomplete review retains a diagnostic artifact and skips ledger and
recurrence publication.

| Outcome | Meaning | Ledger exit code |
|---|---|---|
| `OBSERVED` | All seven daily observations were read. | `0` |
| `INCOMPLETE` | At least one expected daily observation was unavailable. | `2` |
| `NO_OBSERVATIONS` | No daily observation was available. | `2` |

`--allow-no-observations` remains available for ad-hoc rendering. The workflow
requires complete daily coverage.

Before restoring the RCA schedule, verify the existing ADR-0382 handoff using
the repository reader role and seven current S3 envelopes. Check that the
read-only review reports seven distinct observation days and the existing
alert-noise run can read its daily evidence.

## Where these guards run

`alert-rca-ledger.py --self-test` and `standing-critical-digest.py --self-test`
are registered in `.github/gates/gates.yaml`. The existing Python test shard
runs `openbank-infra/scripts/alert_digest_readonly_test.py` against the deployed
relay configuration and the canonical digest and alert-noise scripts.
