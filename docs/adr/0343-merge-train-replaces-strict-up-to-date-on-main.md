---
date: 2026-10-18
decision-status: proposed
delivery-status: partial
followup: "open-bank-oss — ADR-0343 phase 1: ruleset flip + MERGE_TRAIN_SHADOW=false; shadow mode runs today"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance, release-versioning]
summary: "A bot-driven merge train (batched squash of armed green PRs over main, verified by the normal CI, merged in order) replaces strict up-to-date on main-protection; the `merge-train` commit status becomes the required context."
---

# ADR-0343 — Merge train replaces strict up-to-date on main

## Context

`main` is protected by the `main-protection` ruleset with `strict_required_status_checks_policy: true`:
a PR merges only when its required contexts were computed on a head that already contains the
current tip of `main`. That is the correct fix for the defect class measured in ADR-0272 and
documented at length in `CLAUDE.md`: two green PRs that textually merge cleanly and are jointly
invalid, through a registry list, a ratchet baseline, a derived file or an `openapi.yaml`
`info.version`. ADR-0272 measured it over 24.3 h (2026-08-21T13:50Z .. 2026-08-22T14:10Z, queries
pinned in that ADR): **~197 merges/day**, 20 of 197 `main` CI runs red, and **19 of those 20 were
PRs that were themselves green** — the merge was the defect, not the PR. GitHub's merge queue, the
native answer, was rejected there on availability alone: it needs an organisation-owned repository
and this one is user-owned.

Strict up-to-date closes the race by construction and has a throughput ceiling by construction.
A PR can merge only after a CI run on a head that includes the current `main`; every merge
invalidates every other candidate; so at most one merge lands per CI latency, serially:

```
throughput_max = 1 / CI_latency = 1 / 40 min  ≈ 36 merges/day
```

against a measured demand of ~197/day. The repo already pays the symptoms of that ceiling:
`auto-update-armed-prs.yml` exists only to press "Update branch" for armed PRs (one admission per
pass, a debounce, a per-PR budget, a full-fleet serialisation lane — all of it scheduling around
the same serial bottleneck), a branch update restarts the PR's whole CI and adds ~150 jobs to a
shared runner queue for a full-fleet PR, and #8837 went green five times without landing. Strict
mode converts a correctness problem into a throughput problem and then the throughput problem
back into CI load.

What a merge queue actually provides is cheaper than what strict provides: not "every PR was
tested on the exact final `main`", but "every merge was tested **in the order and combination it
lands in**". A batch of N PRs tested once on top of `main` and then merged in that order gives the
same guarantee at 1/N of the CI cost, and the bisect on a red batch bounds the damage to one more
CI round per halving rather than one per PR. That is what this ADR builds, as a bot, because the
platform will not provide it for this repository.

## Decision

We will run a **merge train**: a scheduled bot workflow that batches armed, green PRs, verifies
the batch as one tree with the ordinary CI, and merges the verified PRs in train order. The
`merge-train` commit status it posts becomes a required context on `main-protection`, **pinned to
the gitops App**, and the strict up-to-date policy is turned off. Concretely:

1. **Driver.** `.github/workflows/merge-train.yml` runs every 10 minutes and on
   `workflow_dispatch`, under the concurrency group `merge-train` with `cancel-in-progress: false`.
   It is **three jobs, each on its own runner** (see *Security*): `settle` (App token) judges the
   train whose CI has finished; `build` (no secrets) builds the next train; `publish` (App token)
   verifies it by re-derivation and hands it to CI. All logic is
   `.github/scripts/merge-train.py` (python3 stdlib + `git`), which also carries `--shadow`,
   `--self-test` and `--check-declaration`. One train is in flight at a time.
2. **Candidates.** Open, non-draft, same-repository PRs with auto-merge armed, every required
   context of `main-protection` (other than `merge-train` itself) `success` at the head, no
   `agent-pr-guard` refusal, and no App-posted `merge-train` failure on that head (a culprit waits
   for a push). `codex/` branches are excluded. A PR touching **anything under `.github/`** —
   workflows, gate scripts, `gates.yaml`, generators, the regen inventory — or **any file the
   generator runner would execute** never qualifies; it takes the owner's manual lane (see
   *Security* for why the boundary is the whole directory). "Would execute" is derived from
   `origin/main`'s `regen-derived.sh` itself — its `find <root> -name '<pattern>'` enumeration at
   any depth, the programs its inventory invokes, and the libraries `gen-index.sh` sources — never
   from a fixed-depth glob, which a deeper file walks past. A PR's file list is
   `git diff --name-only <base>...<verified head>`, never the PR files API, which stops at 3000
   entries. Oldest first; the required set is read live from the ruleset.
3. **Fast lane.** `settle` computes it every tick from live data: a candidate whose changed files
   are disjoint from what `main` changed since its merge-base and from every other candidate, and
   touch no protected path (`openbank-libs/**`, `build-logic/**`, `openbank-infra/gitops/**`,
   `docs/adr/**`, `.github/**`, `*/src/main/resources/openapi.yaml`, any regen-inventory source
   read from `origin/main`), gets `merge-train` = success directly. Its own CI already answered
   the only question a train could ask. Nothing about the fast lane is stored between jobs.
4. **Train (`build`).** Over live `origin/main`: `git merge --squash` of each member's verified
   head sha in order; a member that conflicts or is empty is dropped. The generator paths are
   overlaid from `origin/main`, then `regen-derived.sh --all` and `gen-index.sh` run under an
   allowlisted environment and any diff becomes one regeneration commit. The train goes to
   `publish` as one git bundle; it is untrusted.
5. **Verification (`publish`).** The batch is recomputed live. Every member squash is rebuilt with
   `git merge-tree --write-tree` onto publish's **own** chain and must equal the bundle's commit
   tree for that step; publish's own drop list must match. The bundle's single extra commit, if
   any, may touch only the `DERIVED_OUTPUTS` declared in `origin/main`'s `regen-derived.sh`, and
   may add no symlink or gitlink; the final tree may carry no symlink or gitlink `main` lacks.
   Publish then commits its own chain (`git commit-tree` over the verified trees), pushes it to
   `train/<run_id>-<attempt>`, records `tree=<sha>` as an App status on that commit, and opens a
   draft carrier PR labelled `merge-train` so `ci.yml` and `services-ci.yml` run on it.
   `issue-hygiene` is not waited for: it judges a PR body, not a tree.
6. **Green (`settle`, a later tick).** On the carrier's exact head: an App-created record whose
   tree equals the commit's tree, a chain of publish's own commits whose base is live `main`, and
   every required context green. Then, per member in order: the live head must still be the
   recorded head; `merge-train` = success is posted on it and it is squash-merged with the head sha
   pinned. Between merges `main` must have advanced only by the train's own merge; a foreign
   merge stops the run and the rest wait for the next tick.
7. **Red.** A red train of N > 1 members halves across ticks: each member gets an App
   `merge-train` = pending `bisect size N/2` status, and the next batch is capped by the size on
   its oldest member — so the left half runs first, then the right. A red train of one member is
   the culprit: `merge-train` = failure and a fixed-text comment. A train `publish` cannot verify
   for a member-attributable reason (symlink, regeneration outside the inventory) halves the same
   way and, at one member, sends that PR to the manual lane. Two PRs green alone and red together
   resolve themselves: the first merges, the second is red alone over the new `main`.
8. **Ruleset.** `merge-train` is added to the required contexts **with the App's
   `integration_id`**, and `strict_required_status_checks_policy` is set to `false`. Without the
   pin, any workflow granted `statuses: write` — including one a PR edits — could post
   `merge-train` on its own head. This PR changes no ruleset; the owner runs the commands in
   *Rollout*. `check-ruleset-context-parity.py` accepts a required context declared as the
   `STATUS_CONTEXT` of a tracked script, so the gate does not red every PR the moment
   `merge-train` becomes required.

### Security — the invariant

> **The token never shares a runner with PR data; privileged jobs act only on trees they
> re-derived with execution-free git; regeneration content is proven by main's own freshness
> gates on the exact tree.**

**Threat.** Every member of a train is PR-authored, and the derived-file generators execute over
the train tree. Whoever can steer what runs next to the App token can post `merge-train` and merge
— a review bypass for every PR, reachable by anyone who can arm auto-merge. Four designs were
reviewed before this one: generators sharing a job with the token (the self-test's probe leaked
it); a split whose privileged job validated the build's output field by field (each review found
the next unvalidated field — branch names, a stale base, an unbound record, interpolated text, a
regeneration commit judged by its shape); and a single job that rebuilt everything itself but
still shared a runner, `HOME` and `.git` between the generators and the token, where trusted
generator code steered by hostile data (a symlink, a file written outside the worktree) could
reach the later step. Each fix narrowed the gap; only separating runners closes it.

**The security boundary — a stated assumption, not an emergent property.** The `build` job holds
no secret, runs on an ephemeral runner, and **nothing it produces is trusted**. `publish`
re-derives every member commit with `git merge-tree`, bounds the regeneration commit to `main`'s
inventory and refuses symlinks and gitlinks; CI then proves the regeneration content with
`main`'s freshness gates on the exact tree; `settle` merges only what that CI passed on a tree
`publish` recorded. **Arbitrary code execution inside `build` therefore buys an attacker nothing
beyond a red or wasted train.** This is the line of defence. Everything that hardens `build`
itself — the untrainable filter, the quarantine, `PYTHONSAFEPATH`, the scrubbed environment — is
defence in depth: it exists to keep `build` honest so trains are not wasted, and a gap in it is a
reliability defect, not a security one. Reviews should test the boundary, and the self-test does:
it plays a fully compromised `build` that emits, in turn, a regeneration commit rewriting a
non-derived file, swapped PR content under a member's own commit, an extra commit, a symlink, a
smuggled second parent, a train over an older `main`, and a regeneration commit with wrong
content inside the inventory. `publish` rejects the first six; the seventh is published, goes red
on the freshness gate, and `settle` merges nothing. The residual cost of a compromised `build` is
named honestly: a red or refused train blames its member (a `merge-train` failure status or a
manual-lane comment) until that member's author pushes again.

**How the three clauses are held.**
- *No shared runner.* `build` is the only job that runs code over PR data, and it holds no
  secret: read-only `GITHUB_TOKEN`, `persist-credentials: false`, generators taken from
  `origin/main` and run under an allowlisted environment (no token, no `RUNNER_TEMP`, no
  `GITHUB_*`), with `PYTHONSAFEPATH=1` so neither a script's directory nor the cwd is ever on
  `sys.path` — `main`'s payments bundle scripts run `python3 -` from their own directory, where a
  PR-added `re.py` would otherwise shadow `re`. Before any generator runs, `build` removes from the
  working tree every file that is not on `main` *except* the files `main`'s inventory declares as
  generator inputs (a new ADR, a new `version.txt`); those are never untrainable, and
  `PYTHONSAFEPATH` keeps them unimportable. Hiding every added file instead was measured and
  rejected: the generators then miss the PR's own inputs, the derived files come out wrong, and
  `main`'s freshness gate reds the train and blames an innocent member. Every git path list is
  read with `-z` and split on NUL, and any path containing a control character is refused
  outright — git quotes such names in its default output, so string checks silently miss them
  while `find` and `fnmatch` still match them. (All of this is defence in depth; see the boundary
  above.) `publish` and `settle` mint the App token on fresh runners, check out `main`, run
  this script as it exists on `origin/main` under `python3 -I -S`, set
  `GIT_CONFIG_NOSYSTEM=1`, `GIT_CONFIG_GLOBAL=/dev/null` and `core.hooksPath=/dev/null` on every
  git call, and never check out, install or execute anything from a train. The
  `merge-train-declaration` gate holds this per job.
- *Re-derived trees only.* `publish` treats the bundle as a claim: it recomputes the batch live,
  rebuilds each member squash with `git merge-tree --write-tree` (which writes objects, not files)
  over its own commits, and accepts the bundle only where the trees are identical; it then pushes
  **its own** commits, never the bundle's, to a branch name derived from the run
  (`^train/[0-9]+-[0-9]+$` and `git check-ref-format`, the only refs it ever pushes or deletes).
  `settle` reads publish's record back from the commit it is on — an App-created status, so its
  creator is checked — requires the commit's tree to equal it, and walks publish's own commits to
  find the members and the base. Statuses go on verified head shas and merges pin them.
  Comments are fixed text.
- *Regeneration content proven by main's gates.* The one thing publish cannot re-derive without
  running code is what the generators wrote. Publish bounds it — only paths in `DERIVED_OUTPUTS`
  read from `origin/main`, no symlink or gitlink — and CI proves it. Every entry of
  `DERIVED_OUTPUTS` has a checker that recomputes it from its sources and compares, inside a
  required context: the enforced gates `adr-registry-integrity-check`,
  `eu-ai-act-inventory-drift`, `ai-governance-snapshot-drift`, `card-capability-matrix-drift`,
  `slo-dashboard-drift` and `release-registration-consistency` (the `gates (…)` shards), and
  `opa-policy.yml`, which runs the three OPA data generators with `--check` and regenerates every
  bundle ConfigMap to diff it against the committed one. Those gates, `run-gates.py` and the
  workflows that run them are `main`'s in every train, **because no member may touch
  `.github/`** — which is why the disqualification covers the whole directory, not just the
  generators. A Rollout manifest that a bundle script restamps in place is deliberately *not* a
  derived output: the rest of that file is not derived and no gate re-derives it, so a train
  whose regeneration touches one is refused and takes the manual lane.

**What is deliberately NOT required: a review decision.** The train does not check
`reviewDecision == APPROVED`. `main-protection` has no review rule, and the repository has a single
human maintainer (documented in the governance notes): the human gate here is the *arm* step —
auto-merge is enabled by the owner, behind a local hook that refuses to arm money-path and
governance PRs without his own review — together with `agent-pr-guard`, which the train reads off
the member's head and treats as a refusal. Adding an approval requirement would require the sole
human to approve his own PRs, which GitHub forbids, and would stall the train entirely.

**Manual lane.** PRs touching `.github/` or a generator, PRs adding a symlink, and trains whose
regeneration leaves the inventory cannot ride a train. In phase 0 that costs nothing (strict is
still on). In phase 1 such a PR needs the `merge-train` status from a human: the owner reviews it
and posts the status by hand with the App pin in mind (an owner-token status does not satisfy a
context pinned to the App), or merges with the ruleset bypass he already holds. Both leave an
audit trail naming the human. Its volume is a phase-0 measurement.

### What the train is NOT

It is not a replacement for a PR's own CI: a PR that is red on its own head never qualifies. It is
not a scheduler for CI capacity: one train per 10 minutes, at most 8 members, bounds its cost to
one CI run per batch plus one per halving for a culprit. And it is not four-eyes: `agent-pr-guard`,
the money-path approval rules and the security checklist gate are evaluated on the member PR
exactly as today, and a refusal there removes the PR from the candidate set.

## Rollout

**Phase 0 — shadow (this PR, default).** `MERGE_TRAIN_SHADOW=true` in the workflow `env`. The
train builds, verifies and posts `merge-train` statuses, but **never merges**; because nothing
merges, a green train is rebuilt on the next tick, so shadow measures verdicts and bisect
behaviour, not throughput; strict stays on and
nothing requires the context yet. Expected observation: the status appears on armed green PRs,
the train carrier PRs come and go, culprits get comments. Two weeks of shadow is the measurement
that decides phase 1: how many trains went red, how many members were bisected out, and whether
any merge the shadow train would have made disagrees with what strict mode eventually let land.

**Phase 1 — flip.** Requires: (a) shadow has shown the status landing on every armed green PR,
(b) `ruleset-context-parity` is green with `merge-train` present in the required set,
(c) the owner runs the commands below, (d) the workflow `env` is set to
`MERGE_TRAIN_SHADOW=false` in a follow-up PR. `auto-update-armed-prs.yml` then exits before PR
enumeration on its own (it already does when strict is off) and can be retired in a later ADR.

```sh
# 1. find the ruleset id
RS=$(gh api repos/JiRaska/open-bank-oss/rulesets --jq '.[] | select(.name=="main-protection") | .id')
# 2. read the current rules, add merge-train PINNED to the App, turn strict off
#    APP_ID = the gitops App's id (the value of the GITOPS_APP_ID secret)
gh api "repos/JiRaska/open-bank-oss/rulesets/$RS" > /tmp/rs.json
APP_ID=<gitops-app-id> python3 - <<'PY'
import json, os
rs = json.load(open('/tmp/rs.json'))
for r in rs['rules']:
    if r['type'] == 'required_status_checks':
        p = r['parameters']
        p['required_status_checks'] = [c for c in p['required_status_checks'] if c['context'] != 'merge-train']
        # Pin the context to the gitops App, so no other actor's status can satisfy it.
        p['required_status_checks'].append({'context': 'merge-train', 'integration_id': int(os.environ['APP_ID'])})
        p['strict_required_status_checks_policy'] = False
json.dump({'rules': rs['rules']}, open('/tmp/rs-patch.json', 'w'))
PY
gh api -X PUT "repos/JiRaska/open-bank-oss/rulesets/$RS" --input /tmp/rs-patch.json
# 3. verify by reading it back — the PUT answers 200 whether or not the rule changed
gh api repos/JiRaska/open-bank-oss/rules/branches/main \
  --jq '.[] | select(.type=="required_status_checks") | .parameters | {strict: .strict_required_status_checks_policy, ctx: [.required_status_checks[] | {context, integration_id}]}'
```

**Rollback.** Re-enable strict and remove the context, in that order (removing the context
first, with strict still off, is the one state with no protection at all); then set
`MERGE_TRAIN_SHADOW=true` or disable the workflow. Same PUT with
`strict_required_status_checks_policy: true` and the `merge-train` entry filtered out. Every PR
that merged through the train is an ordinary squash merge with its own CI history; nothing to
undo in git.

## Alternatives considered

- **GitHub merge queue** — the native implementation of exactly this design. Rejected in
  ADR-0272 on availability: organisation-owned repositories only. Re-adopt the day the repo moves
  to an organisation; the `merge_group` readiness check in `ruleset-context-parity` already
  exists for that.
- **Keep strict and scale `auto-update-armed-prs`** — the status quo. Its ceiling is structural
  (one merge per CI latency) and every mitigation in it is scheduling around that ceiling at the
  cost of CI re-runs. Rejected on the arithmetic above, not on its implementation.
- **Turn strict off and keep detection** — ADR-0048's original choice for the `info.version`
  race. At ~197 merges/day it yields ~20 red `main` runs a day, each of which blocks every open PR
  on a gate (`check-adr-registry.sh`, the registry shard) until someone fixes `main`. Rejected by
  the ADR-0272 measurement.
- **Rebase-and-retest each PR serially by bot** — the same ceiling as strict with more moving
  parts. Rejected.
- **Verify the train with a push to a `train/*` branch instead of a draft PR** — cheaper (no PR
  object), but `ci.yml`/`services-ci.yml` run on `push` only for `main`, and adding `train/**`
  there would double every path filter and concurrency key. The draft PR reuses the existing lane
  byte for byte. Revisit if carrier PRs become noise in the PR list.

## Consequences

**Positive**
- Throughput bound rises from `1/CI_latency` to `batch/CI_latency` (8× at the default), with the
  same "tested in the combination it lands in" guarantee that strict provides for one PR.
- The derived-file class — the measured majority of green-PR breaks — is regenerated once per
  batch, on the exact tree that lands, instead of once per PR on a tree that will not.
- Branch updates and their CI re-runs stop: a member is never rebased, its head and its CI
  history stay what the author pushed.
- A culprit is identified by bisection and told which contexts failed on which train, which is
  more than a red `main` after the fact ever says.

**Negative**
- Latency per PR rises for the uncontended case: a lone green PR waits for the next tick and one
  train CI run instead of merging on its own green (the fast lane removes most of this for PRs
  that touch nothing shared).
- A foreign merge during a train — a human merging by hand, release-please, a deploy-bot PR —
  defers the train's remaining members unless its files are disjoint. The train is only correct
  if it is the only thing merging armed work; that is a process rule, and in phase 1 the
  required context enforces it for every PR, since nothing else posts `merge-train`.
- One more bot with the App token that can merge PRs. Its blast radius is bounded by the same
  ruleset as any other merger (`agent-pr-guard`, signatures) — it only ever squashes PRs that
  were already armed — and by the invariant above: the token never shares a runner with PR data.
- Bisection spans ticks: a red batch of 8 costs up to three more carrier CI runs (halves of 4, 2,
  1) to isolate one culprit, each a tick or more apart. That is slower than bisecting within one
  run, and is the price of `settle` never consuming state a build produced.
- PRs that touch a generator path (`.github/scripts/**` in particular, which gate work does
  routinely) take the manual lane in phase 1. The volume of that lane is a phase-0 measurement.
- Carrier PRs: up to one every 10 minutes, closed on completion. They carry the `merge-train`
  label so every PR-list query can filter them out.

**Neutral**
- `check-ruleset-context-parity.py` gains a second source of satisfiable contexts (a declared
  `STATUS_CONTEXT` in a tracked script). That is a widening of the gate and is derived from the
  script, not from a list in the checker.
- `auto-update-armed-prs.yml` becomes inert at phase 1 (it already exits when strict is off) and
  is retired separately.

## Compliance impact

- PCI DSS: not applicable — CI merge mechanics; no cardholder-data path changes.
- DORA:    not applicable — every merge remains a reviewed PR with its own CI record, plus a
  train record; the change-management evidence a PR carries does not change.
- GDPR:    not applicable — no personal data.
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- ADR-0272 — merge queue for main (rejected on availability; the measurement this ADR builds on)
- ADR-0048 — API contract versioning (the `info.version` race, "detection over prevention")
- ADR-0254 — ruleset context parity gate
- `.github/workflows/auto-update-armed-prs.yml` — the strict-mode workaround this supersedes
- `.github/workflows/merge-train.yml`, `.github/scripts/merge-train.py`
