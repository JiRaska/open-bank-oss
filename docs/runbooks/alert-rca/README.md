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

## Observation coverage — an empty week is not a clean week

The daily standing-critical digest archives an observation envelope on **every**
day it succeeds, including a day on which zero critical alerts were firing
(`{"observedAt": ..., "alerts": []}`). A genuinely quiet week therefore still
produces roughly seven envelopes.

So **zero envelopes does not mean "nothing fired"** — it means the producer
never ran, and the weekly review measured nothing at all. The two states are
reported separately and never share an exit code:

| Outcome | Meaning | Exit code | Review renders |
|---|---|---|---|
| `OBSERVED` | envelopes covered the window; the findings describe what was measured | `0` | the normal review |
| `NO_OBSERVATIONS` | no envelope covered the window; the producer is down | `2` | a `CAUTION` banner, and every finding reads **Unknown** rather than "none" |

`--allow-no-observations` downgrades the second case to exit `0`. It exists for
local and ad-hoc rendering only and is deliberately **opt-in**: the scheduled
review never passes it, so a week with no data fails loudly instead of
publishing a clean-looking report about nothing.

This distinction is the reason the ledger's `--self-test` drives the real CLI in
a subprocess and asserts on the **exit code**, not only on the rendered prose.
Flipping `--allow-no-observations` from opt-in to the default changes no string
in the output and survived every in-process assertion — a guard is proven by
what it prevents, and here what it must prevent is a green run.

## Where these guards run

`alert-rca-ledger.py --self-test` and `standing-critical-digest.py --self-test`
are registered in `.github/gates/gates.yaml` (`alert-rca-ledger-guards`,
`standing-critical-digest-guards`, shard `lint`), so they run on **every PR**.

Being a step inside the scheduled workflow is not sufficient and was the
original gap: both scheduled workflows fail at their first Alertmanager step
while no `ALERTMANAGER_*` credential exists, so the self-tests had never
executed on any lane. `check-gate-script-registration.py` could not catch that —
it scopes itself to `check-*` filenames, and its own header records that it does
not check *reachability*.
