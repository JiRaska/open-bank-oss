---
date: 2026-11-03
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance]
summary: "When a strict-lane reservation ends (merged or released at stall/ceiling) and green fast-lane PRs wait, the next reservation starts one bounded turn later; main never moves under a reserved head."
---

# ADR-0360 — Fast lane gets a turn whenever a strict-lane reservation ends

## Context

ADR-0343's strict lane reserves main for one manual-lane PR (executable `.github/`, gates, generators,
inventory) until its CI on an up-to-date head finishes, and merges it only on a head that contains live
main. While a reservation runs the fast lane is held, because a fast merge would move main, put the
reserved head behind it, and throw away a CI run that already took hours. That hold is correct.

The defect is BETWEEN reservations. The train has a turn after a strict merge (`TRAIN_TURN_S`), but the
fast lane does not: fast-lane PRs are not train candidates, so with strict PRs queued the next strict
update-branch is issued in the tick right after a reservation ends, and the fast lane is held again.
A reservation that is released at the 240 min ceiling (no merge) does not even trigger the train's turn.

Measured on 2026-11-02: no merge to main from 18:27Z for more than 5.5 h. Strict #14595 and #14607 each ran
into the 240 min ceiling on the saturated 20-slot hosted pool and were released, #14644 was reserved
straight after, and fast-lane PRs #14523, #14610 and #14620 were logged "fast held: main reserved for
strict-lane #N" in every tick. In the week from 2026-10-27, main had 7 gaps of more than 3 h (the longest
487 min), and 77 of 259 merges touched strict-lane paths.

## Decision

We will give the fast lane one turn whenever a strict-lane reservation ENDS: main's head is a strict-lane
merge, or a reservation over the current main was released (stall or ceiling). If green fast-lane PRs wait
that were never posted, the strict lane does not start the next reservation in that tick; the fast lane
posts them over the current main and they merge before any head is reserved.

- The turn is recorded by an App-only `merge-train/fast-turn` status on main's head (`opened <epoch>`) and
  lasts at most `FAST_TURN_S` (30 min, two ticks of the cron). If the marker cannot be written there is no
  turn, which is today's behaviour.
- The turn ends early when every waiting fast PR is posted, or when a fast merge moves main: the new head
  is neither a strict merge nor released over, so there is exactly one turn per reservation end.
- A running reservation is never affected, main red opens no turn, and shadow mode writes nothing.

## Alternatives considered

- **Let the fast lane merge during a reservation** and accept the holder's verdict when the fast merges are
  path-disjoint from it, or re-run CI on the merge ref at merge time. Rejected: a strict-lane PR is by
  definition gate, workflow or generator code, whose effect is not path-local (a new gate judges files it
  never touches). Path disjointness is the fast lane's own argument and does not cover that; ADR-0272
  found 19 of 20 red mains came from green PRs. Re-verifying at merge time means a second full fleet CI on
  the same saturated pool, so the holder never finishes while fast merges keep arriving. #14411 (narrow
  fleet CI) was closed for false negatives, so a cheaper re-verification is not available. #14493 builds
  this window behind a flag that is off until CI result reuse exists.
- **Shorter reservations or a fixed fast slot every N minutes during one.** Rejected: any fast merge under
  a reservation restarts the holder's CI from zero (90-240 min measured), so a slot inside a reservation
  multiplies strict latency instead of interleaving. The bounded deferral of ADR-0343 already lets one
  CARRIER in once per reservation; it never serves the fast lane, which is the lane that was frozen.
- **Batch strict PRs.** Rejected as the fix: only 24% were measured batchable, and a red batch of gate
  code needs bisection at full-fleet CI cost per step.
- **Revert to strict up-to-date plus auto-merge.** Rejected: it serialises every PR (not only manual-lane
  ones) behind a fresh full CI each, which is what ADR-0343 replaced; it was 25-60 merges a day on an
  unsaturated pool and cannot be better on this one.

## Consequences

**Positive**
- The fast backlog drains once per reservation, so fast latency is bounded by one reservation (at most the
  240 min ceiling) plus one tick, instead of by the whole strict queue.
- Correctness is unchanged: main never moves under a reserved head, and nothing reaches main on a weaker
  check than before.

**Negative**
- Strict latency gains at most one tick per reservation end (30 min worst case if a fast PR is posted and
  never merges).
- The strict lane itself is still serial; ten strict PRs still take ten reservations.

**Neutral**
- One more App-only status context on main's commits.

## Compliance impact

- PCI DSS: not applicable — internal CI merge ordering, no cardholder data path.
- DORA:    not applicable — no change to ICT incident, resilience or third-party controls.
- GDPR:    not applicable — no personal data processed.
- PSD2:    not applicable — no payment or SCA flow touched.
- CNB:     not applicable — no regulatory reporting touched.

The change does not weaken change control: every merge is still green on the required contexts, and every
strict-lane merge still runs on a head that contains live main.

## References

- ADR-0343 (merge train, strict lane, bounded deferral, train's turn)
- `.github/scripts/merge-train.py`: `fast_turn`, `FAST_TURN_S`, `FAST_TURN_CONTEXT`
- #14493 (fast turn after a strict merge only, plus a parallel window behind a flag), #14411, #14302
