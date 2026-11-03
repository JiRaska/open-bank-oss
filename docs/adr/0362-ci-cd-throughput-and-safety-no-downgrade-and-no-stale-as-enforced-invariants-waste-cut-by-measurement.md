---
date: 2026-11-03
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, gitops, governance]
summary: "No-downgrade and no-stale become enforced invariants (a GitOps pin only moves forward from live main; every test script is run by something), and CI waste is cut only where a 7-day measurement locates it: evidence churn, main-red watch, full-fleet deploy PRs."
followup: "#14698 #14700 — pin gate and reachability gate shipped as PRs; auto-deploy union-of-newer-pins, merge-train self-test call budget and the deploy-PR fleet exemption are planned"
---

# ADR-0362 — CI/CD throughput and safety: no-downgrade and no-stale as enforced invariants, waste cut by measurement

## Context

ADR-0343 decided the merge train and the strict lane; ADR-0358 decided (planned) that image pins
leave `main` for a bot-owned deploy branch; ADR-0254 decided the gate-estate integrity checks;
ADR-0272 rejected GitHub's merge queue on availability. None of them decides two things the owner
now states as non-negotiable: **a deploy must never roll a service back** (a revert is allowed only
as an explicit revert), and **CI must not be spent on stale things**. This ADR is needed because
both were assumed to hold and were measured not to, and because the pool that pays for every
false assumption is fixed: 20 concurrent GitHub-hosted jobs, no new paid runners (the owner
rejected dedicated ARC capacity).

### What was measured (2026-10-27T11:54Z .. 2026-11-03T13:00Z, commands recorded)

All run data: `gh api -X GET repos/JiRaska/open-bank-oss/actions/runs --paginate -f per_page=100
-f created=<day> -f event=<event>` per day and event, re-pulled per workflow id where a cell hit
GitHub's 1000-result listing cap (`main red watch`/`workflow_run` still caps on three days, so its
figures are lower bounds). Job-level data: `.../actions/runs/<id>/jobs?filter=latest` over a random
sample of 148 Services CI PR runs, 59 push runs, 60 CI PR runs, 40 CI push runs, 9 merge-train
runs. 34,018 runs in the window.

| Measure | Value | Denominator |
|---|---|---|
| Run wall minutes, top consumers (7 d) | Services CI / PR **48,139**; Ruleset bypass evidence / workflow_run **37,106** (3,443 runs); Services CI / push 28,492; CI / PR 23,442; main red watch 11,703 + 5,557 (6,167 runs); Merge train 9,387 (3,449 runs, **2,803 cancelled**) | 34,018 runs |
| Services CI PR run | **165 job-min/run**, queue p50 **7.5 min**, p95 **51.6 min** (4,328 jobs) | 148 sampled runs |
| Services CI push run | 159 job-min/run, queue p50 0.3, p95 33.5 | 59 sampled runs |
| CI PR / push run | 18 / 30 job-min/run, queue p95 42.6 / 32.4 | 60 / 40 sampled runs |
| Full-fleet share of PR build minutes | runs with ≥40 build jobs are **38 %** of Services CI PR runs and **90 %** of PR build minutes (497 build-min each) | 68 sampled runs with build jobs |
| Deploy-PR CI | 52 Services CI runs on `chore/gitops-auto-deploy-*` / `chore/admin-ui-deploy-*` heads, avg **77.7 min** wall, 86 build jobs on #14659 — a manifest-only diff | 7 d |
| Carrier CI | 51 Services CI runs on `train/*`, 2,922 wall-min; #14627 and #14647 closed unmerged after ~1 h green each | 7 d |
| Superseded-head waste | PR runs overlapped by a newer push on the same branch and **not** cancelled: Security regression test 10, Issue hygiene 8, main red watch 3, Platform OpenTofu 2 — **0 for Services CI, CI, Security scan, OPA policy** (all four already `cancel-in-progress` keyed by ref) | 10,407 PR runs |
| Unreferenced test scripts | **21 of 132** under `.github/scripts` named by no gate, workflow or discover pattern; 14 after one-hop invocations; 1 failing on main (#14697) | `find .github/scripts -name 'test-*' ...` vs `gates.yaml` + workflows |
| Deploy PR #14579 | 34 pins moved: **34 forward** against its merge-base, **22 backward** against live main; `mergeable=CONFLICTING` is the only thing stopping it | `check-deploy-pin-monotonic.py --base <merge-base>` with `origin/main` present |
| `merge-train-declaration` self-test | **18,534 subprocess spawns**, all through one helper (`sh()` at merge-train.py:855): rev-list 3,226, diff 2,936, show 2,127, rev-parse 1,960, fetch 1,270, `rev-parse main` 1,216, merge-base 831, **clone 616**; spawn time is 95 % of wall (692 of 721 s on a loaded Mac; 77–109 s on `main`, 134.8 and 182.5 s on #14685 against a 120 s budget) | one instrumented run, `subprocess.run` wrapped |

Two of the owner's rules were therefore false in practice. **No-downgrade** held only by the
coincidence that an older deploy PR's pin lines textually collide with main's newer ones;
`supersede-deploy-prs.sh` compares open deploy PRs with each other, never with the pins main
holds, and `auto-deploy.yml` writes `sandbox-<own sha>` for every selected service against
whatever main carries. **No-stale** held for the expensive workflows (cancel-in-progress is
already everywhere it matters — the candidate "add run-level cancel-in-progress" is rejected
below as already done) but not for the test estate, where a fifth of the test scripts were
consulted by nothing.

## Decision

We will treat no-downgrade and no-stale as **enforced invariants with a gate each**, and cut CI
spend only where the measurement above locates it, in this order of value per risk:

1. **A GitOps image pin only moves forward along main's history** — gate `deploy-pin-monotonic`
   (enforced, `check-deploy-pin-monotonic.py`, #14698). Every `image: ...:sandbox-<sha>` line a
   PR moves must move to a descendant of the sha **live main** holds for that image now (not the
   merge-base: that is the measured blind spot). `behind`, `diverged` and `unknown` all fail; the
   one escape is the `deploy-revert` label, read off every `PR-Number:` member for a train
   carrier, so a revert is always explicit. Shipped.
2. **Every test script under `.github/scripts` is run by a gate or a workflow** — gate
   `test-script-reachability` (enforced, `check-test-script-reachability.py`, #14700), with the
   13 orphans wired into two gates in the same change. Only `run:`/`selftest:` values count as
   invocations; a mention in a rationale, a name, a `paths:` filter or a comment does not — the
   first draft was fooled by its own rationale and the self-test holds that case. Shipped.
3. **Auto-deploy writes the union of newer pins, not its own sha for every service** — the
   preventive half of (1): when the workflow rewrites a pin and main's current pin is already a
   descendant of the source sha, it leaves main's value. Then a slow build for an older commit
   produces a PR that carries only the services it genuinely advances, and the gate in (1)
   becomes a backstop that never fires in normal operation. Planned; touches `auto-deploy.yml`
   (1,503 lines, edited by the open deploy-PR work) and is sequenced after (1) merges.
4. **The merge-train self-test gets a spawn budget, not a time budget.** 18,534 spawns at
   ~5 ms each on `ubuntu-latest` is the 90 s; raising `budget_seconds` (#14685: 120 → 180) is
   whack-a-mole. The fix is to count: a self-test assertion that the harness issued fewer than N
   git invocations, with N ratcheted down as the hot helpers are batched (`rev-list`/`merge-base`
   ancestry via one `git rev-list --parents` walk per tick; `show`/`ls-tree` reads via
   `cat-file --batch`; the 616 clones via one template clone plus `git worktree add` or
   `clone --shared`). No case is removed. Planned, coordinated with #14685's branch.
5. **A pin-only deploy PR does not build the service fleet.** 52 runs/week at ~500 build-min
   each (~3,700 hosted job-min/day, 13 % of the 28,800-job-min/day pool) on diffs that contain
   no code. `derive-fleet-scanning-test-modules.py` already reports "no changed module" for a
   component manifest path, so the full-fleet decision comes from elsewhere in the PR selector;
   the exemption must be decided on diff **content** (every hunk under `openbank-infra/gitops`
   is an `image:` pin line), never on path, because #14411 was closed for exactly the path-based
   false negatives. Planned, behind a measurement of which selector branch fires.
6. **Rejected: run-level cancel-in-progress for superseded heads.** Measured 0 overlapped,
   uncancelled runs for Services CI, CI, Security scan and OPA policy; the 10 + 8 + 3 + 2 on
   small workflows are worth ~1,100 min/week in total and two of those workflows are keyed
   per-PR by design. Not worth a change.
7. **Deferred, not decided here:** evidence churn (`ruleset-bypass-evidence.yml`, 37,106
   wall-min/7 d — #14624 is open and addresses it), merge-train trigger churn (2,803 of 3,449
   runs cancelled — #14644), the fast-lane turn after a strict reservation (#14652), admission
   prerequisites (#14685). This ADR builds beside them and touches none of their files.

## Alternatives considered

- **Keep relying on the textual conflict to stop a downgrade.** It stopped #14579 today. It does
  not stop a service whose pin line main did not touch, nor an update-branch or rebase that keeps
  the PR's side, and it reports as `CONFLICTING`, which reads as a merge problem rather than a
  rollback. Rejected: a coincidence is not a control.
- **Judge pins against the PR's merge-base (a normal diff-scoped gate).** Simpler, needs no fetch.
  Measured: passes #14579 (34 of 34 forward). Rejected on that measurement; the self-test keeps the
  blind spot as a case so nobody re-derives it.
- **Wait for ADR-0358 (pins on a deploy branch) instead of gating pins on main.** 0358 is
  planned, not shipped, and the invariant is the same on any branch; the gate reads the base
  branch from the event, so it carries over. Rejected as a reason to wait.
- **Make the discover patterns catch everything (`-p 'test*'`).** `unittest discover` does not
  run shell or Node tests and does not recurse into package-less directories; it would still
  miss `tests/*.sh` and a hyphenated test in a subdirectory. Rejected as incomplete; it is the
  shape that produced the 21.
- **Shrink strict-lane full-fleet CI to the affected set by path (#14411).** Closed for false
  negatives: Kotlin tests that execute `.github/scripts/*`, Python imports through `gatelib`,
  transitive hops through `check-*` hubs. Rejected again here; (5) is content-based and a strict
  subset, which is the only safe shape that measurement left.
- **Raise the self-test budget again.** It went 120 → 180 in #14685 and the run then hit 182.5 s.
  Rejected: the quantity that grows is the spawn count, so that is the quantity to bound.

## Consequences

**Positive**
- A deploy PR that would roll a service back cannot merge unseen; a revert is a labelled PR.
- A committed test that nothing runs is red on the PR that commits it, not found by audit.
- The CI pool is spent on a measured list, not on the candidates that felt expensive.

**Negative**
- The pin gate needs live main: one `git fetch --depth=1` or one contents-API read per changed
  manifest on deploy PRs (zero on every other PR). An unreadable main fails closed and costs one
  re-dispatch.
- Two new wiring gates add ~130 s of test time to two shards (budgets 240 and 120 s), paid on
  every PR for 13 suites that previously ran never.
- A residual race remains for (1) until (3) lands: a fast-lane deploy PR whose last CI predates
  a later deploy merge was judged against that run's main.

**Neutral**
- Gates: `deploy-pin-monotonic` (enforced, gitops), `test-script-reachability` (enforced, lint),
  `deploy-script-tests-previously-unreachable` (enforced, supplychain),
  `ci-script-tests-previously-unreachable` (enforced, lint). Items 3–5 have no gate yet; (4) is
  itself the self-test of an existing gate.

### Delivery check

```
python3 .github/scripts/run-gates.py --list | grep -E 'deploy-pin-monotonic|test-script-reachability|previously-unreachable'
# expected: four ids, all `enforced`
python3 .github/scripts/check-test-script-reachability.py --root .
# expected: `0 orphan(s)` and no known-unreachable line once #14697 has merged
grep -c 'TAG="sandbox-' .github/workflows/auto-deploy.yml
# (3) delivered when the pin write consults main's pin: the line gains a descendant check
python3 .github/scripts/merge-train.py --self-test 2>&1 | grep -E '^spawns='
# (4) delivered when the self-test prints its spawn count and the number is under its declared ceiling
```

## Compliance impact

- PCI DSS: not applicable — CI mechanics; no cardholder data path changes
- DORA:    not applicable — no ICT third-party or incident-reporting duty is cited in this ADR
- GDPR:    not applicable — no personal data
- PSD2:    not applicable — no payment interface changes
- CNB:     not applicable — no reporting obligation is cited

## References

- ADR-0343 (merge train, strict lane), ADR-0358 (pins on a deploy branch, planned), ADR-0254
  (gate-estate integrity), ADR-0272 (merge queue rejected), ADR-0053 (runner pool)
- Issues #14698 (pin downgrade), #14700 (unreferenced tests), #9785, #6231, #12182 (deploy-PR
  supersession), #14697 (the rotted test), #14411 (path-based fleet shrink, closed)
- Open PRs built beside, not on: #14624, #14644, #14652, #14685, #14689
