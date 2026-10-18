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
`merge-train` commit status it posts becomes a required context on `main-protection`, and the
strict up-to-date policy is turned off. Concretely:

1. **Driver.** `.github/workflows/merge-train.yml` runs every 10 minutes and on
   `workflow_dispatch`, under the concurrency group `merge-train` with `cancel-in-progress: false`
   (a train in flight is never cancelled by the next tick; the tick waits). It is **one job with
   three ordered steps** (see *Security*): A rebuilds the trains with no App token in existence;
   B compares the tree CI ran on with the tree A built; C mints the gitops GitHub App token — as
   `auto-update-armed-prs.yml` does, because a push made with `GITHUB_TOKEN` triggers no
   workflows — and pushes, posts and merges. All logic lives in `.github/scripts/merge-train.py`
   (python3 stdlib + `gh`/`git`), which carries `rebuild`, `publish`, `--dry-run`, `--shadow`,
   `--self-test` and `--check-declaration`.
2. **Candidates.** Open, non-draft, same-repository PRs with auto-merge armed, every required
   context of `main-protection` (other than `merge-train` itself) `success` at the PR head, and no
   `agent-pr-guard` refusal on that head. Branches under `codex/` are excluded. Oldest PR first,
   at most `TRAIN_BATCH_SIZE` (default 8) per train. The required set is read live from the
   ruleset, never from a list in the script. A PR that touches a **generator path**
   (`.github/scripts/**`, `docs/adr/*.sh`, any `gen-*` under `openbank-infra/gitops/`) never
   qualifies: it would be running its own change with the generators' authority. It goes to the
   owner's manual lane (below).
3. **Fast lane.** Before any train is built, a candidate whose changed files are disjoint from
   `git diff --name-only <merge-base> origin/main` and touch none of `openbank-libs/**`,
   `build-logic/**`, `openbank-infra/gitops/**`, `docs/adr/**`, `.github/**`,
   `*/src/main/resources/openapi.yaml`, nor any path fragment in the `regen-derived.sh` generator
   inventory, gets `merge-train` = success directly. Its own CI already answered the only question
   a train could ask: nothing it changed interacts with anything that moved.
4. **Train.** In step A, over **live** `origin/main` as the base: `git merge --squash` of each
   candidate's verified head sha, in order, committed with the PR title as subject, the PR body's
   first paragraph and a `PR-Number: #n` trailer. A PR that does not apply cleanly is **dropped
   from this train** with a fixed-text PR comment, not failed. After stacking, the generator paths
   are overlaid from `origin/main`, asserted unchanged, and `bash .github/scripts/regen-derived.sh
   --all` plus `bash docs/adr/gen-index.sh` run under a scrubbed, allowlisted environment; any diff
   is committed as `chore(train): regenerate derived files`. The derived-file class is the majority
   of the measured breaks, and the train is the one place where regeneration sees the whole batch.
   Step A builds the whole batch **and every bisect half** (2N-1 trains over the same base) up
   front, so that nothing is built after the token exists.
5. **Verification.** Per train the bisect asks for, step C pushes the commit step A built to
   `train/<run_id>-<attempt>` and keeps one **draft PR** labelled `merge-train` open from it to
   `main` — `ci.yml` and `services-ci.yml` run on `push` only for `main`, so the draft PR is the
   CI carrier; each bisect step force-pushes the next train to the same branch. Step B reads the
   carrier's live head, requires its tree to equal the tree step A recorded, and only then reads
   the required contexts there. `issue-hygiene` is excluded from the wait set: it judges a PR's
   body, not the tree, and was already green on every member. The carrier is closed and the branch
   deleted when the run ends. If `main` moved between step A and step C, the tick ends without
   acting and the next one rebuilds.
6. **Green.** For each PR in order: post status `merge-train` = success on the PR head
   (`verified on train <sha> over main <sha>`), then squash-merge it through `gh`. Between merges
   the script asserts that `main` advanced **only** by the train's own merge. If a foreign commit
   landed, it stops and defers the rest to the next train — unless the foreign commit's files are
   disjoint from the whole train's files under the fast-lane rule, in which case it continues: the
   argument that admits a PR to the fast lane admits the train past a disjoint foreign merge.
7. **Red.** Bisect by halving over the halves step A built (each its own train over the same
   `main`), verified the same way. A half of one that is red is the culprit: it gets a fixed-text
   PR comment pointing at the run and a `merge-train` = failure status on its head, which blocks it until the
   author pushes. Only the longest verified **prefix** merges; every other green PR is deferred to
   the next train, because it was verified over `main`, not over the prefix. Two PRs that are each
   green alone and red together have no single culprit; the first merges, the second is retried on
   the new `main` by the next train and fails there on its own, which identifies it.
8. **Ruleset.** `merge-train` is added to the required contexts and
   `strict_required_status_checks_policy` is set to `false`. This PR changes no ruleset; the owner
   does it with the commands in *Rollout*. `check-ruleset-context-parity.py` is extended so that a
   required context may also be satisfied by a tracked script that declares it as the
   `STATUS_CONTEXT` it posts via the commit-status API — otherwise the gate would, correctly, red
   every PR the moment `merge-train` became required, since no `pull_request` job emits it.

### Security — the privileged step acts only on trees it built itself

**Invariant.** *The step that holds the App token acts only on trees it built itself, from
verified heads, with trusted generators.* Everything else — PR heads, CI results, anything an
earlier design handed between jobs — is untrusted, and is used at most as a hint compared hash
against hash with what the script built.

**Why the invariant, not a list of checks.** Every member of a train is PR-authored, and the
derived-file generators *execute* over the train tree. The first prototype ran them with the App
token in the inherited environment — a review bypass for every PR, reachable by anyone who can arm
auto-merge; the self-test's exfiltration probe leaked the token before the fix. The second split
the work into a token-less build job and a privileged publish job, and the third had publish
validate what build produced, field by field. Each review found the next field that validation did
not cover — a plan-chosen branch name reaching `git push`/`--delete`, a stale base accepted
because it was an ancestor of `main`, a record not bound to the batch it claimed, plan text
interpolated into a comment, a regeneration commit accepted because its lines *looked* like a
checksum restamp. All five share one cause: the privileged side was *consuming* another
process's output. Validation of consumed output is open-ended; not consuming it is not.

**How the workflow holds it.**
- *Step A (no App token exists yet).* The base is live `origin/main`, read in this step. Candidates
  are listed live and `qualifies()` runs on live checks and live files; each head is fetched and
  must equal the API's head sha, and the train squashes that **sha**, never a movable ref. A PR
  that touches a generator path never qualifies. The generator paths are overlaid from
  `origin/main` and the train is refused if one still differs (modified or added), so only trusted
  generators run — over PR *data*. They run with an explicit allowlist (`PATH`, `HOME`, locale,
  the git identity), never an inherited environment: no token, no `RUNNER_TEMP`, no `GITHUB_*`.
  Every record — key, commit, tree, applied, dropped — is computed here for the batch it was
  built for.
- *Step B.* CI is untrusted by nature; it only reports on a tree. The tree is read off the
  carrier's live head and must be byte-identical to the tree step A recorded.
- *Step C (App token minted here, and only here).* It acts only if live `main` still equals the
  base step A used, and only for members whose live head still equals the verified sha and that
  still qualify. It pushes this run's own commits to `train/<run_id>-<attempt>` — a name derived
  from the run, checked against `^train/[0-9]+-[0-9]+$` and `git check-ref-format`; nothing else
  is ever pushed or deleted. Statuses are posted on the verified sha and merges use
  `--match-head-commit` with it. Comments are fixed text; no PR-, branch- or commit-derived string
  is interpolated.
- *The declaration gate* holds the order textually: the rebuild step precedes the token mint,
  references no App secret, and no artifact or generator invocation appears in the workflow.

**Residual assumption, stated.** Steps A and C share a job and a filesystem; the state step A
writes for step C is this script's own output, not a generator's. That is sound only while the
generators execute no PR-supplied code — which is what the generator-path disqualification and
the overlay assertion enforce — and step C re-reads `main`, every head and every check live
before acting regardless.

**What is deliberately NOT required: a review decision.** The train does not check
`reviewDecision == APPROVED`. `main-protection` has no review rule, and the repository has a single
human maintainer (documented in the governance notes): the human gate here is the *arm* step —
auto-merge is enabled by the owner, behind a local hook that refuses to arm money-path and
governance PRs without his own review — together with `agent-pr-guard`, which the train reads off
the member's head and treats as a refusal. Adding an approval requirement would require the sole
human to approve his own PRs, which GitHub forbids, and would stall the train entirely.

**Manual lane.** A PR that modifies a generator path cannot ride a train, by the rule above. In
phase 0 that costs nothing (strict is still on). In phase 1 such a PR needs the `merge-train`
status from a human: the owner reviews it and posts the status by hand
(`gh api -X POST repos/JiRaska/open-bank-oss/statuses/<head-sha> -f state=success -f context=merge-train -f description="manual lane: generator change reviewed by owner"`),
or merges with the ruleset bypass he already holds. Both leave an audit trail naming the human.

### What the train is NOT

It is not a replacement for a PR's own CI: a PR that is red on its own head never qualifies. It is
not a scheduler for CI capacity: one train per 10 minutes, at most 8 members, bounds its cost to
one CI run per batch plus one per halving for a culprit. And it is not four-eyes: `agent-pr-guard`,
the money-path approval rules and the security checklist gate are evaluated on the member PR
exactly as today, and a refusal there removes the PR from the candidate set.

## Rollout

**Phase 0 — shadow (this PR, default).** `MERGE_TRAIN_SHADOW=true` in the workflow `env`. The
train builds, verifies and posts `merge-train` statuses, but **never merges**; strict stays on and
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
# 2. read the current rules, add merge-train to required_status_checks, turn strict off
gh api "repos/JiRaska/open-bank-oss/rulesets/$RS" > /tmp/rs.json
python3 - <<'PY'
import json
rs = json.load(open('/tmp/rs.json'))
for r in rs['rules']:
    if r['type'] == 'required_status_checks':
        p = r['parameters']
        if not any(c['context'] == 'merge-train' for c in p['required_status_checks']):
            p['required_status_checks'].append({'context': 'merge-train'})
        p['strict_required_status_checks_policy'] = False
json.dump({'rules': rs['rules']}, open('/tmp/rs-patch.json', 'w'))
PY
gh api -X PUT "repos/JiRaska/open-bank-oss/rulesets/$RS" --input /tmp/rs-patch.json
# 3. verify by reading it back — the PUT answers 200 whether or not the rule changed
gh api repos/JiRaska/open-bank-oss/rules/branches/main \
  --jq '.[] | select(.type=="required_status_checks") | .parameters | {strict: .strict_required_status_checks_policy, ctx: [.required_status_checks[].context]}'
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
  were already armed — and by the invariant above: the token exists only in the step that acts on
  trees the script built itself.
- Step A builds 2N-1 trains per tick (15 at the default batch of 8), each with a full
  regeneration, so that nothing is built after the token exists. That is CPU, not CI fan-out; the
  carrier pushes, which are the expensive part, are still one per bisect step actually taken.
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
