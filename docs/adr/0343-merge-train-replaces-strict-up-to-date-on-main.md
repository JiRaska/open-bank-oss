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
   for a push). `codex/` branches are excluded. A PR touching **anything under `.github/` that
   can change what executes** — every workflow and action file, every script of any extension
   (and any extension-less file), `gates.yaml` (its run commands), pip requirement pins, agent
   prompts, the regen inventory — or **any file the generator runner would execute** never
   qualifies; it takes the owner's manual lane. Under `.github/` only `*.md` and `*.txt` outside
   those places ride a train: prose, and gate-input data such as `*-baseline.txt` (see
   *Security — gate inputs* for why that adds no exposure). "Would execute" is derived from
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
   overlaid from `origin/main` — the generators only: a trainable data file under an overlay root
   (a baseline, an ADR) keeps the member's content — then `regen-derived.sh --all` and `gen-index.sh` run under an
   allowlisted environment and any diff becomes one regeneration commit. The train goes to
   `publish` as one git bundle; it is untrusted.
5. **Verification (`publish`).** `build` hands over its declared batch (`members.txt`: number and
   head sha per member) with the bundle; publish admits it only if every entry is a live train
   candidate at exactly its live head and the batch respects the size cap, so the manifest can
   only select among PRs that already qualify. (Re-planning the batch live instead rejected a
   legitimate bundle whenever any candidate changed state between the two jobs — shadow run
   39139647341, where one PR went green mid-build and shifted the batch.) Every member squash is rebuilt with
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
  read with `-z` and split on NUL; every gating diff goes through one helper that passes
  `--no-renames` and returns both paths of any rename or copy (with rename detection on, git names
  only the NEW path, so renaming `regen-derived.sh` to `docs/x.md` would hide that a gate script
  was deleted); and any path containing a control character is refused
  outright — git quotes such names in its default output, so string checks silently miss them
  while `find` and `fnmatch` still match them. (All of this is defence in depth; see the boundary
  above.) `publish` and `settle` mint the App token on fresh runners, check out `main`, run
  this script as it exists on `origin/main` under `python3 -I -S`, set
  `GIT_CONFIG_NOSYSTEM=1`, `GIT_CONFIG_GLOBAL=/dev/null` and `core.hooksPath=/dev/null` on every
  git call, and never check out, install or execute anything from a train. The
  `merge-train-declaration` gate holds this per job.
- *Re-derived trees only.* `publish` treats the bundle as a claim: it admits build's declared batch
  only entry by entry against live candidacy,
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
  workflows that run them are `main`'s in every train, **because no member may touch anything
  under `.github/` that executes or decides what executes** — workflows, actions, scripts,
  `gates.yaml`, requirement pins, agent prompts. A Rollout manifest that a bundle script restamps in place is deliberately *not* a
  derived output: the rest of that file is not derived and no gate re-derives it, so a train
  whose regeneration touches one is refused and takes the manual lane.

**Security — gate inputs (baselines, allowlists) may ride a train.** A `*-baseline.txt` or an
allowlist under `.github/` is data that main's gate scripts read; it executes nothing. A PR that
loosens one gets no new exposure from a train: (1) the owner reviews it at arm time behind the
same local hook as today, and arming is the human gate the train relies on; (2) the train's CI
runs `main`'s gate scripts over that baseline exactly as the PR's own CI already did, and the
member only qualifies because that CI was green; (3) in `build` the generators are `main`'s, run
over the baseline as data in an environment with no secret, exactly as they run over
`rules.yaml`. What stays out is anything under `.github/` whose content is *interpreted as
instructions*: requirement pins (they choose what pip installs), agent prompts (they instruct an
agent that holds tools), and every non-`.md`/`.txt` file, extension-less ones included, because a
path rule cannot tell data from code there. Measured on the shadow run 39145205203: 8 of 25
listed PRs were in the manual lane under the whole-directory rule; under this rule 7 are, because
the other seven each also touch a gate script, `gates.yaml` or a workflow.

**What is deliberately NOT required: a review decision.** The train does not check
`reviewDecision == APPROVED`. `main-protection` has no review rule, and the repository has a single
human maintainer (documented in the governance notes): the human gate here is the *arm* step —
auto-merge is enabled by the owner, behind a local hook that refuses to arm money-path and
governance PRs without his own review — together with `agent-pr-guard`, which the train reads off
the member's head and treats as a refusal. Adding an approval requirement would require the sole
human to approve his own PRs, which GitHub forbids, and would stall the train entirely.

**Manual lane = the strict lane.** PRs touching an executable or execution-deciding path under
`.github/`, a generator, `gates.yaml` or the derived-output inventory never ride a train: the build
job would execute them. In phase 1 they are not stranded either — the first phase-1 run showed why
they would be: an owner-token status cannot satisfy a context pinned to the App, and the ruleset has
no bypass actors. So `settle` gives them a *strict lane*, which restores per PR exactly the
guarantee strict up-to-date gave every PR: CI green on a tree that already contains main.
For each armed, non-draft PR that `qualifies()` refuses ONLY for the manual-lane reason, settle
(App token, git and the API only, executing no PR code):
1. if `git merge-base --is-ancestor <live main> <head>` holds and every required context other
   than `merge-train` is green on that head, posts `merge-train` success
   (`strict lane: green on an up-to-date head over main <sha12>`) and squash-merges pinned to that
   head — after re-reading live main, and never while a train carrier is in flight (a strict-lane
   merge would move the carrier's base; the carrier's own "main moved since publish" check also
   rejects it if one ever does), and at most one per tick, since a merge makes every other
   strict-lane head behind;
2. if the head is up to date and its required CI is still running — including not yet reported, the
   normal state for the first minutes after update-branch — (or has just gone green), *reserves*
   main: settle reports in-flight, so no train is built under it; no other strict-lane PR is updated;
   **and nothing else may move main** — the fast lane posts no status (GitHub auto-merge acts on it)
   and a carrier that finished green is held, not merged. Main moves only when the reserved PR
   settles: merged, failed, or timed out. Holding only other strict-lane updates was not enough: on
   2026-10-20 #13456 was updated at 23:18Z, fast-lane #13490 merged at 23:29Z while its CI ran, it
   was updated again at 23:53Z, and fast-lane #13492 merged one minute later — re-updated on every
   cycle, so it could starve forever. The reservation is read before any carrier is settled, and an
   update-branch starts it in the same tick, before the fast lane is posted;
3. if it is green but behind, calls update-branch (pinned to the judged head) for the oldest such PR
   only — one per tick, none while another is reserved — so CI is never flooded. Its CI then runs
   and a later tick merges it. A reservation is bounded by `RESERVATION_TIMEOUT_S`, twice the p95 of
   green pull-request CI (measured: p50 17 min, p95 32 min, so 64 min), counted from the head's
   committer time, which update-branch sets (a committer time in the future, which only an author can
   write, counts as timed out): past it the reservation is released, the fast lane and
   trains resume, and the PR goes to the back of the strict queue, so a hung CI cannot freeze main.
   The cost is bounded too: fast-lane and train merges wait at most one strict-lane CI cycle. A
   file-disjoint fast lane during the reservation was rejected: any merge makes the reserved head
   behind, and since strict up-to-date is what this lane restores, re-proving it without a new update
   would mean a second definition of "up to date". With strict off `auto-update-armed-prs.yml` exits before enumerating
   PRs, so the train owns this update.
Refusals other than the manual-lane one keep a PR out of this lane: red, pending, disarmed, draft,
fork, `codex/`, a `merge-train` failure, `agent-pr-guard`, a path with a control character, and a
symlink or gitlink. **The human gate is unchanged:** only ARMED PRs are considered, and arming
money-path, governance and workflow PRs is already human-only through the owner's local hook — the
strict lane adds no new way for an unarmed or agent-armed workflow change to reach main.

**Stale-red refresh.** When `main` itself breaks, every armed PR whose CI runs meanwhile goes red on
that context — and stays red after main is fixed, because neither the train nor the strict lane
touches a red PR (on the first day of phase 1 a broken deploy-hold gate left seven PRs to be
updated by hand). So `settle` refreshes a red PR whose red is *main's*, not its own. An armed,
non-draft, same-repo PR that `agent-pr-guard` did not refuse is refreshed only when all hold:
1. a required context other than `merge-train` is FAILURE on its head;
2. its head is behind live main (`merge-base --is-ancestor` fails);
3. every such failing context is attributable to main: at `mb = merge-base(head, live main)` — or
   the nearest first-parent ancestor of `mb` with a completed result — main's own CI reported that
   same context as FAILURE, and on the live tip (or its nearest ancestor with a completed result,
   i.e. the latest completed main run) it is now green.

Then settle calls update-branch for it (`stale-red refresh: <context> was red on main <mb12>, green
on <tip12>`, reported under `refreshed`), oldest first, at most two per tick, never in shadow.
**It cannot loop.** After the refresh the PR's merge-base *is* the fixed main, on which the context is
green, so if the PR is still red, condition 3 fails — the red is now its own and it is left alone;
and a PR that is not behind fails condition 2 outright. A PR red on its own merits (the context was
green on main at its base) is never refreshed at all. Like the strict lane it uses the App token,
git queries and the API only, and executes no PR code; it adds no merge, only the update a human
would otherwise click.

**Main-red awareness.** The strict lane served manual-lane PRs oldest-first and updated the oldest
green-but-behind one onto live main whatever main's state. With main red on a required context,
that update could only manufacture main-caused red: the old PR went red on main's gate, the next
oldest was updated onto the same red tip, and the PR carrying the fix — newer, so last in line —
was never served (2026-10-20: `gates (gitops-api)` red on main, the fix waited while settle reported
`updated: [<older PR>]` and then nothing). So at the start of settle, `main_red` is the set of
required contexts (merge-train aside) that are FAILURE in main's latest completed CI at the live
tip (the tip, or its nearest first-parent ancestor with a completed result — a fix that has merged
keeps main red until its own push run reports). Then, in the strict lane only:
1. a PR whose failing required contexts are all in `main_red` is **waiting**
   (`strict: waiting for main (<ctx> red on main <sha12>)`, reported under `waiting`): it cannot
   go green until main is fixed, so it is never served and never reserves main;
2. **no update-branch is issued onto a red main** to any PR: a behind PR simply waits, and the
   stale-red refresh (which already requires the context green on the tip) updates it once main
   is green;
3. **the fix is preferred**: a strict-lane PR green on every required context, whose merge-base
   (or nearest ancestor with a result) was red on a `main_red` context, demonstrably carries the
   fix. It is ranked first and merges **without being up to date**
   (`strict lane: fix for <ctx> red on main <sha12>, green over its red base`). Every other
   condition still applies — armed, not draft, same repo, no excluded prefix, `agent-pr-guard` not
   refusing, no symlink/gitlink or control-character path, no train in flight, never in shadow —
   and live main is re-read immediately before the merge. The fix holds the strict-lane
   reservation (it is the holder, ahead of any up-to-date PR), so neither the fast lane nor a train
   moves main under it in the tick it merges; a waiting PR (1) is never the holder.

*Trade-off of (3).* The fix is merged over a main it has not been tested against: commits that
landed between its base and the tip are unverified in combination with it. That is accepted
because (a) main is already red, so the strict guarantee it gives up is one main has already
lost; (b) the PR's own CI ran the failing gate green over a base on which main's CI had it red,
which is the strongest available evidence that it is the remedy; (c) the alternative — updating
it onto the red tip and waiting a CI cycle — is exactly what deadlocked, since any older PR takes
the lane first. Being red-based is required, not merely green: a PR whose CI ran over a still-
green base proves nothing about the breakage and is not treated as a fix. A fix that is wrong in
combination shows up on main's own next push run, as any red main does. When main is green none
of this applies and the strict lane behaves exactly as before.

*Every lane, not only the strict lane (#13400).* Rule 3 originally applied only to manual-lane PRs,
and a red main also reached the other two lanes: a train built over the red tip came back red on the
main-red context whatever its members did, and settle named its single member the culprit for a
check main itself was failing. So, while `main_red` is non-empty:
4. **a fix is served first in every lane**: a train/fast-lane candidate that passes the same
   `fix_contexts` test as rule 3 leaves the train and the fast lane and is served by the strict
   lane as a fix — first, alone, without update-branch, live main re-read before the merge, the
   merge pinned to its head (`sha`, i.e. `--match-head-commit`);
5. **no train is planned over a red main**: it could only come back red on the main-red context,
   so building it would spend a CI run to learn nothing; the fast lane is unchanged;
6. **no culprit for a main-red context**: a carrier red only on contexts in `main_red` is
   inconclusive (closed, no status, no comment, no bisect); a carrier also red on a context main is
   green on still blames, and the culprit status names only those contexts.
And every open PR leaves a decision line in the settle log, armed or not: a disarmed or draft PR was
filtered out in silence, so a log with no line for it could not say whether it was ever seen.

### What the train is NOT

It is not a replacement for a PR's own CI: a PR that is red on its own head never qualifies. It is
not a scheduler for CI capacity: one train per 10 minutes, at most 8 members, bounds its cost to
one CI run per batch plus one per halving for a culprit. And it is not four-eyes: `agent-pr-guard`,
the money-path approval rules and the security checklist gate are evaluated on the member PR
exactly as today, and a refusal there removes the PR from the candidate set.

### Amendment (2026-10-20) — carriers are judged by their members; cancelled is inconclusive

The first live hour of phase 1 marked two innocent members as culprits, one per defect (#13400).

**Rule 1 — a body gate judges a carrier's MEMBERS.** A gate that reads the live PR body judged the
carrier's bot boilerplate, so `security-checklist-money-path` failed every train holding a
money-path member (#12506: carriers #13424/#13451 red on that alone, while #12506's own body had
all six boxes ticked). The attestation is a human's, on the member; copying ticks onto the carrier
would turn it into bot text, so that is never done. Instead the gate recognises a **verified
carrier** — ALL of: head branch `^train/[0-9]+-[0-9]+$` in this repository, author exactly
`openbank-gitops-bot[bot]` of type `Bot`, label `merge-train`, all read from the live PR object —
derives the members from the carrier's own commits (`PR-Number: #N` trailers on the train's member
commits over `base..head`; any other commit fails closed) and requires each member's LIVE body to
pass the same rule against that member's own diff (its train commit). A human-opened `train/…` PR is
an ordinary PR. The single regeneration commit is not a member's diff: it is bounded to
DERIVED_OUTPUTS and proven by main's freshness gates on the same tree. The carrier identity lives in
`.github/scripts/merge_train_carrier.py`, held equal to `merge-train.py` by the checklist self-test.

Per gate that reads the PR body or PR metadata:

| Gate | Carrier problem? | Decision |
|---|---|---|
| `security-checklist-money-path` | yes (the #12506 defect) | members judged on their own bodies and diffs |
| `coverage-floor-ratchet` | yes, latent: a member's `coverage-floor-lowered:` trailer is in its body, never on the carrier | trailer text also read from the verified carrier's member bodies |
| `issue-hygiene` | no | already exempt for `[bot]` authors, and the train already excludes it (`TREE_INDEPENDENT_CONTEXTS`); each member is judged on its own PR |
| `security-regression-test` | no verdict problem: the carrier body closes nothing, so the rule never applies | members are judged on their own PRs; see rule 2 for its trigger fix |
| `release-scope-mismatch-gate` | title-based and advisory (exit 0) | unchanged |
| `agent-pr-guard` | no: it reads the event author, branch and diff; the carrier's `train/` branch is outside every agent prefix | unchanged |
| `api-contract` (migration assertion) | yes, latent: the `## Idempotency caller migration` section lives in the member body | section read from the verified carrier's member bodies too (each line names its service) |

**Rule 2 — a cancelled job is INCONCLUSIVE, never red.** #13137 was named culprit because carrier
#13452's `security-regression-test` was cancelled by concurrency ("a higher priority waiting
request") with no step run. In `settle`, a required context that is `cancelled` or `stale`, or
`skipped` next to a cancellation on the same head, is inconclusive: it never bisects and never
blames. If nothing failed and something is inconclusive, settle re-runs the cancelled jobs of that
carrier **once** (App `merge-train/rerun` status as the marker) and the carrier is in flight again;
still inconclusive after that, or a refused rerun, closes the carrier with no verdict and the next
tick rebuilds. A real failure next to a cancellation still blames, and the culprit status names only
the really failing contexts (`culprit: <contexts> red on a train over main <sha>`). The two competing
runs had one cause: `security-regression-test` triggered on `labeled`, and a carrier is opened and
then labelled `merge-train`, so the `labeled` run cancelled the `opened` one. That workflow never
reads PR labels (its scope is the closed issue's `security` label), so `labeled`/`unlabeled` were
dropped — cheaper and more correct than reordering the carrier's opening, which cannot attach a
label in the same API call.

**Clearing the wrong marks.** Culprit statuses written before this amendment start with
`isolated as the cause of a red train`. Such a status is no longer final: the member re-qualifies,
rides one more train, and that train overwrites the status either way — `success` when the old mark
was an artifact (#12506, #13137), a `culprit: …` failure when it was real. No new status is written
with the legacy text, so the mechanism retires itself; nobody needs to push an empty commit.

### Amendment (2026-10-21) — a gate's budget overrun or NOT EVALUATED is inconclusive (#13400)

Carrier #13501 (CI run 39526539131), the first carrier judged in carrier mode, named #13137 culprit
for two gates that passed on content — `security-checklist-money-path` printed "every member of the
carrier passes on its own body" in 33.7 s and `coverage-floor-ratchet` "none lowered" in 44.9 s —
and went red only on their 20 s `budget_seconds`. Measured over the last 10 carrier and 12 ordinary
PR runs, the same gates took 0.2–5.9 s everywhere else; the nine other carriers ran before carrier
mode existed, so this one run was the whole carrier-mode sample. The cost was not the API: each gate
called `git fetch --deepen=128 origin` in the depth-2 checkout, which deepens every one of the
remote's ~665 branch refs — 29.4 s from a depth-2 clone, against 1.7 s for
`git fetch --no-tags --deepen=128 origin <head sha>`, which yields the same walkable chain. Every
gate repeated it, because the checkout stays shallow after a deepen.

**Rule 3 — carrier facts are resolved once per job, under one deadline.**
`merge_train_carrier.resolve()` reads the live PR, the member chain (deepening only the head's
history, and not at all when the chain is already walkable) and every member body (one GraphQL
query), within 15 s, and writes the result to run-gates' per-job cache directory keyed by PR number,
head sha and base; every later gate in the job reads that file. `coverage-floor-ratchet` asks only
once it has found a lowering, so a carrier that lowers nothing costs it no fetch at all.

**Rule 4 — an unreadable subject is NOT EVALUATED, never PASS and never a finding.** A timed-out or
failed read (the PR object on a `train/…` head, the chain, a member body that the verdict needs)
makes the gate print `GATE_VERDICT=NOT_EVALUATED` and exit non-zero: red, so an unjudged train never
merges, but not a statement about any member. Previously an unreadable member body was judged as
empty and blamed the member.

**Rule 5 — settle reads the gate runner's verdict, not the log.** `run-gates.py --json` records a
`failure_kind` per gate (`content`, `budget`, `not_evaluated`, `timeout`, `unfalsified`, `setup`);
a budget overrun is `budget` only when the gate passed on content. In `settle`, a carrier whose
failing required contexts come from one CI run whose every failed job is a gate shard (or the
`Validate manifests` aggregate), and whose every failed gate in those shards' `gate-results-<slug>`
artifacts is `budget` or `not_evaluated`, is INCONCLUSIVE exactly as under Rule 2: re-run once, then
closed with no verdict. A missing or unreadable artifact, a record without `failure_kind`, any other
failed job, or any content failure keeps the verdict conclusive. Budgets were not raised: with the
fetch fixed, carrier mode is not legitimately slower than an ordinary run.

### Amendment (2026-10-21) — bounded strict deferral (#13400)

A strict reservation whose head CI is still running yields to an already-green carrier at most once per strict head (recorded as the App's `merge-train/strict-deferral` status on that head and inherited by its update-branch through the first parent); a green strict head is never pre-empted, and after the one yield the strict PR has priority until it merges or fails — N ready PRs merge now for one extra CI cycle of one PR, and the strict PR still merges within two cycles.

### Amendment (2026-10-21) — extra gates: a red security scan blocks serving

The train judged only the ruleset-required contexts, so a PR with a red `Security scan / Trivy`
was served while sessions disarmed it by hand (`--disable-auto`): two automations enforcing
opposite policies, and nothing merged. **This replaces manual disarming.** The policy now has one
declaration, `merge_train.extra_gates` in `openbank-libs/governance/rules.yaml` (today: `Trivy`,
the `Security scan` job that runs on every `pull_request` with no path filter), read from live main
by every lane — train candidates, fast lane, strict lane, fix-first — and by carrier judgement. On a
PR head: red = not served (`skip  extra gate red: <name>`, one fixed comment per head, the PR stays
armed); running = wait; never triggered, skipped or neutral = not applicable. A carrier red on an
extra gate is a verdict like any required context, unless main is red on that gate too: then it
counts as main red — PRs wait and the carrier closes with no verdict, nobody blamed.
Each gate is pinned to its producer (`{workflow: .github/workflows/security.yml, check: Trivy}`): only
`github-actions` check runs from that workflow count, and any red one of them is red, so a same-name
run from another app or workflow can neither turn it green nor mask a red. The declaration fails
closed: missing or unparseable on main means `::error::` and no PR is served, no carrier judged, and
`--check-declaration` (which runs on every PR, and re-self-tests on a `rules.yaml` change) fails.

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
`MERGE_TRAIN_SHADOW=false` in a follow-up PR. Phase 1 is gated at runtime on the ruleset: with the flag
off, `settle` still merges nothing (it behaves as shadow and logs why) until the live ruleset requires
`merge-train` pinned to the App (`GITOPS_APP_ID`) and strict is off, so the order of (c) and (d) does
not matter. `auto-update-armed-prs.yml` then exits before PR
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

**Rollback.** The ruleset revert alone stops all merging by the train (the runtime guard sees the pin
or strict change on the next tick); the env flag need not change. Re-enable strict and remove the context, in that order (removing the context
first, with strict still off, is the one state with no protection at all); optionally set
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
  routinely) take the strict lane in phase 1: serial, one update-and-CI cycle per PR, with main
  reserved while it runs — strict mode's throughput for roughly a third of PRs, the train's for the rest.
- Carrier PRs: up to one every 10 minutes, closed on completion. They carry the `merge-train`
  label so every PR-list query can filter them out.

**Neutral**
- `check-ruleset-context-parity.py` gains a second source of satisfiable contexts (a declared
  `STATUS_CONTEXT` in a tracked script). That is a widening of the gate and is derived from the
  script, not from a list in the checker.
- `auto-update-armed-prs.yml` becomes inert at phase 1 (it already exits when strict is off) and
  is retired separately; the strict lane in `settle` takes over the one update it still owed
  (manual-lane PRs).

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
