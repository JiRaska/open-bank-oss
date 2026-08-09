# `.github/` — CI, gates and workflow pitfalls

> Path-scoped: this loads when you touch `.github/`. It holds the lessons that fire
> INSIDE this tree. Anything that fires fleet-wide, or from outside it (editing
> `rules.yaml`, an `application.yaml`, or merging a PR), stays in the root `CLAUDE.md`
> — the split is by WHERE THE LESSON FIRES, never by which heading it sat under.


### CI gates — exercise the failure path before trusting the green

- **Gates are DECLARED in [`.github/gates/gates.yaml`](.github/gates/gates.yaml), not written as
  workflow steps.** Add an entry (`id`/`group`/`mode`/`selftest`/`run`) and it runs; there is
  nothing to edit in `ci.yml`. Run one locally with
  `python3 .github/scripts/run-gates.py --only <id>`, a whole shard with `--group <g>`, and see
  the set with `--list`. Two things the manifest fixes that are easy to re-break: `mode:` states
  advisory-vs-enforced **outright** (inferring it from a step name is how the registration gate
  once flagged itself, #2450), and `selftest_expect:` states which exit code proves falsifiability
  — `pass` for a checker's own `--self-test` harness (every one in this repo today), `fail` when
  the command *is* the known-positive. Guessing that is silent in the safe-looking direction.
  Shards are wall-time buckets, not a taxonomy — rebalance `group:` when one gets slow, and note
  that the gate count no longer costs wall time linearly, which is the point (79 serial steps
  took `ci.yml`'s median 0.7 -> 2.4 min in four weeks on a REQUIRED check every PR pays).
- **An advisory gate's "these findings are all benign" note is an unverified claim, and advisory
  mode is what removes the pressure to check it.** The repo already knows a gate that has only ever
  passed is unfalsified; the sharper form is that a gate can fire CORRECTLY and have its *triage* be
  the unfalsified artifact. `incluster-hostname-resolution` landed advisory with 6 findings and a
  note — repeated in `gates.yaml` and `rules.yaml` — saying all six were dead `openbank-<svc>`
  rest-client defaults "on services whose pods override them by env". Three were. The other three
  were live: settlement-service's Rollout and onboarding-service's Deployment carried no
  `*_SERVICE_URL` at all, so `openbank-balance-service:8080` / `openbank-ledger-service:8080` /
  `openbank-party-service:8090` were the values those pods actually dialled — names that resolve in
  no namespace, on ports wrong even for the right name (8103 / 8101 / 8111). Every settlement
  debit/credit and GL posting, and onboarding's abandoned-registration party lookup, went nowhere
  (#3931). The note was written from the SHAPE of the config line — a bare `openbank-` default, of a
  kind several peers DO override — instead of from the deployed manifest, and that heuristic gets
  the benign cases right and the live ones wrong. **Grade a finding against the deployed state, one
  workload at a time; "several of these are overridden" is not a fact about the rest.** The trap
  that makes it cheap to misread: `payments-services.yaml` DOES declare `LEDGER_SERVICE_URL` +
  `BALANCE_SERVICE_URL` — for transaction-service, ~2000 lines from settlement's Rollout, so a grep
  of the file confirms the wrong thing. And settlement is a `kind: Rollout`, so a Deployment-only
  parse reports it as having no workload rather than as having no override.
- **A red advisory check and a verified-benign one are indistinguishable — so an advisory finding
  needs a dated verification note or it decays into permanent background noise.** This is what makes
  the bullet above a structural problem rather than one bad call: nothing downstream of a triage note
  re-derives it, no gate covers it, and the longer it sits the more it reads as settled. If you write
  "known, benign", write what you checked and when; if you can't, leave the finding untriaged, which
  is at least honest.
- **An advisory check over a *generated* artifact is a contradiction.** On a hand-written artifact a
  red advisory check means "someone should look"; on a generated one it means "the committed document
  does not match reality" — there is no judgement left to exercise, so advisory just makes the drift
  mergeable. `eu-ai-act-registry` went red twice on #2156 and the PR merged anyway, leaving the EU AI
  Act inventory omitting an AI system until it was regenerated (#2216).
- **The gate that never existed beats the unfalsified one: check whether anything reads the artifact
  at all before assuming a green covers it.** This repo is a MULTI-LICENSE tree — Apache-2.0 at the
  root, an AGPL-3.0-only open-core subset (ADR-0136 + ADR-0181/0193) — and *nothing* compared the
  per-file SPDX headers to that declaration: no `reuse-tool`/`licensee`/`license-eye`/`scancode`
  anywhere, and the OpenSSF Scorecard `License` check only asks whether a recognized licence file
  exists at the **root**, never what the files say. So four descriptions of the split drifted in
  silence: 12 AGPL modules in the tree, 10 in `rules.yaml`, **4** in the published `NOTICE`/`README` —
  which told every downstream adopter the other 8 were Apache-2.0 (#2280). Two transferable rules.
  (a) **A licence claim about a *distributed* artifact needs a gate, not prose** — all 12 carry a
  `version.txt`. (b) **Never let a published doc keep its own copy of a list that lives in
  `rules.yaml`** — enumerate once, point at it everywhere else; the second hand-maintained copy IS the
  drift. Root cause was `scripts/add-license-headers.sh` hardcoding Apache-2.0 for every path, the
  ADR-0136 follow-up nobody did — a stamping script that is not path-aware in a multi-license tree
  manufactures the violation. Now `rules.yaml: agpl_modules` is canonical and
  `.github/scripts/check-license-headers.py` enforces every declaration against it. Frozen headers
  (an applied Flyway migration — editing one breaks its checksum and the service dies at boot) go in
  `REUSE.toml` with `precedence = "override"`, never edited in place.
- **A text-matching guard flags the very text that explains the bug it exists to catch — decide that
  precedence before the first run.** Three in one session, each caught only by running the new guard
  against a case it must NOT flag: `check-roles-allowed-realm.py` reported finrep as still broken
  because #2403's fix quotes the old `@RolesAllowed("SERVICE", …)` in a KDoc (fixed by stripping
  comments — Kotlin's block comments NEST, so mirror that or a KDoc containing `/*` closes early);
  `check-advisory-gate-registration.py` flagged **itself**, its own step being named
  `advisory-gate registration (…, enforced)` (fixed by making `enforced` in a step name beat
  `advisory`); and the `platform-admin` prose in `DevOpsResource` survived the sweep that renamed
  the annotation, because comments are stripped *by design* (#2450). Generalize: a guard over source
  text needs an explicit rule for code-about-code, and stale prose naming a dead identifier is
  invisible to it forever — grep the prose separately after any vocabulary rename.
- **The same collision runs the other way, and that direction is silent: a check greps a file for the
  string it wants, and matches the COMMENT that explains why the string is there.** A false positive
  announces itself; this one reads as a pass. On #3072 a test asserted `middleware.ts` excludes
  `/api/gate` with a whole-file `toMatch(/api\/gate/)` — the exclusion is explained by a five-line
  comment directly above it that names the path three times, so deleting the exclusion itself left
  the test green. Fix: strip comments, then assert against the **construct**, not the file
  (`config.matcher`, the annotation value, the specific key) — a whole-file grep can never
  distinguish the thing from the prose about the thing. Same PR, same class, second instance: an
  Ingress/allow-list agreement check built its tool set with `[a-z0-9-]+`, so a typo'd
  `?tool=grafanaX` matched on the `grafana` prefix and it reported agreement with a tool the gate
  does not know. Both were found only by feeding the assertions the exact broken input they exist to
  reject — a new assertion that has only ever seen the correct file is unfalsified.
- **An "advisory" gate is usually advisory INSIDE the script, not via `continue-on-error`** — 11 of
  12 here print `::warning` and exit 0 unless passed `--enforce`. A sweep for `continue-on-error:
  true` therefore finds one and silently reports the other eleven as enforced. Check both forms
  before claiming anything about what blocks (#2392).
- **A `paths:`-filtered workflow can never be a required status check — so its red is advisory no
  matter how the job is written.** GitHub holds a required context that does not report as
  permanently "Expected — waiting for status", so requiring a path-filtered workflow blocks every PR
  that touches none of its paths. That is why all five required contexts (`all-green`,
  `Validate manifests`, `Gitleaks`, `issue-hygiene`, `OPA policy gate`) run unconditionally. The
  established fix is not to require the small workflow but to put the **binding copy of the script**
  in the unconditional `Validate manifests` job in `ci.yml` and keep the path-filtered workflow as a
  fast echo — what `adr-registry` already did, and what `agent-charter-registry` and `eu-ai-act` now
  do too. Corollary: reaching for the ruleset is usually the wrong instinct here, since the in-repo
  fix ships in the same PR as the gate and needs no GitHub-side config change.
- **A PR that is conflicted AT CREATION never gets `refs/pull/<n>/merge`, so NO `pull_request`
  workflow is ever created — and zero checks renders as "waiting", never as broken.** Not skipped:
  absent. Nothing reports, the required contexts can never be satisfied, and there is no run to
  re-run, so the PR is unmergeable forever and eventually gets closed by whoever supersedes it.
  `DIRTY` alone is NOT the predictor and that is what makes it hard to see — a PR that became
  conflicted *later* keeps the merge ref it was born with along with its full check set (#3058:
  DIRTY, 13 runs). Measured 2026-08-01: four admin-ui deploy PRs with 0 runs, three closed having
  deployed nothing, while every one that merged had 18. The fix on an affected PR is any new head —
  merging `main` in took #3183 from `merge ref 0 / 0 runs` to `1 / 12`, and it then merged. Root
  cause class: a bot that checks out `github.sha` and branches from it while `main` moves under it
  (#3194). Probe with `git ls-remote origin refs/pull/<n>/merge` and a run count per head SHA —
  a PR list, a check list and `gh pr view` all look normal.
- **`/actions/runs/<id>/jobs` returns the LATEST attempt — anything reacting to `workflow_run`
  must query `/actions/runs/<id>/attempts/<n>/jobs`.** The unscoped endpoint silently answers
  about a *different* run than the event fired for, so a guard that inspects job or step
  conclusions reads a re-run's green attempt and declines to act — no error, just a no-op that
  only happens once a human has touched the run first. Caught in #2892 by testing the real
  script against a fixture that had been re-run by hand: it reported "no spot-kill signature"
  for a run whose attempt 1 was unambiguously spot-killed. If the job's `if:` already pins
  `run_attempt`, pin the query to match.
- **A job log CONTAINS the step's own `run:` script, so grepping it matches strings that never
  executed.** GitHub prints the script into the log header, which means every `echo "…"` in it
  appears whether or not that branch ran — a naive `grep 'treating it as a real failure'` reports
  a hit on *every* run of the step, forever. Read only the output after the **LAST**
  `##[endgroup]` (there are several; the last one closes the `Run …` block):
  `awk '/##\[endgroup\]/{n=NR} {a[NR]=$0} END{for(i=n+1;i<=NR;i++) print a[i]}'`.
  Validated on a real pair: the naive grep reports 1 hit on a run where the clean pipeline
  reports 0, and the 0 is right. Cost two wrong conclusions on 2026-07-31, the second from a
  monitor built minutes after diagnosing the first — the fix is easy, noticing is not, because
  a false positive here looks exactly like the success you were hoping for. Prefer structured
  data outright where it exists: step conclusions from
  `gh api .../actions/jobs/<id> --jq '.steps[]'` cannot be spoofed by the script listing.
  In a MULTI-STEP job the last-`##[endgroup]` trick is not enough — it lands you after the final
  step, not inside the one that failed. There the discriminator is **substitution**: real output
  has the variable expanded, the echoed script still has the literal. `grep 'failed to boot'`
  matched an `echo "::error::[${svc}] failed to boot"` that never ran; `grep 'failed to boot' |
  grep -v '\${'` found the one line that did. Took three attempts on #3024 *after* the bullet
  above was already written, so treat "my grep found it" as a hypothesis until the match shows a
  value the script could not have contained.
- **A gate is only as reachable as the JOB it sits in — check the job's `if:`, not just the
  script.** `gates.yaml` exists so gates run unconditionally; a gate written as an inline `run:`
  step inherits its host job's conditions instead, and a conditional host silently narrows the
  gate's scope to something nobody declared. `check-dockerfile-no-build-stage.py` — the #3016
  gate that owns per-service Dockerfile shape — lived in `ci.yml`'s `ui-build`, whose
  `if: needs.changes-ui.outputs.changed == 'true'` fires only on `openbank-admin-ui/`,
  `*/governance.yaml` or the governance schema. **So it could not run on a Dockerfile-only PR**,
  the exact change it exists to catch. Not skipped-and-reported: the job is *absent*, the
  aggregate check is green, and nothing anywhere says a gate was not consulted. Measured on
  #3629 (53 Dockerfiles edited): `Validate manifests` SUCCESS with 3 steps — it is an aggregator
  over the `gates (...)` shards — and `Admin UI build` SKIPPED. Read the *steps* of the job that
  claims to have run your gate (`gh api .../actions/jobs/<id> --jq '.steps[]'`); a green job name
  is not evidence your step was in it. Fix is always the same: declare it in
  `.github/gates/gates.yaml` with a `group:`, never as an inline step in a conditional job.
- **`main`'s own CI conclusion is the one signal with no reader — every check in this repo is
  about a PR.** A red push-triggered run on `main` has no PR to carry it, no reviewer, and no
  notification; it is a red dot in the Actions tab, and the next PR opened against that commit
  *inherits* a failure it did not cause. `main` went red four times on 2026-08-07/08 in three
  independent ways (`Services CI`/engagement-service, `CI`/Admin UI build, `Security scan`/Trivy
  twice) and every one was found because a human happened to sweep; two PRs merged onto it
  meanwhile. The near-miss that made it invisible is worth knowing on its own:
  `deploy-drift-watch.yml` is *named* "Deployed == main watch" and compares the **deployed image**
  to `main` — so a red commit that deployed reads as perfectly in sync. `main-red-watch.yml` +
  `check-main-red-watch.py` now close it (#4019), and two details decide whether such a watcher
  works at all: query `/actions/runs/<id>/attempts/<n>/jobs`, never the unscoped
  `/actions/runs/<id>/jobs` (which returns the LATEST attempt, so one hand-re-run makes the
  watcher answer about a different run than the event fired for, silently); and read
  `.steps[].conclusion`, never the log, since a job log contains the step's own `run:` script.
  Its coverage set is *derived*, not hand-kept — a new push-on-main workflow fails the
  `main-red-watch-declaration` gate until it is watched or excluded with a reason.
- **An oversized `run:` script makes the WHOLE workflow unparseable — and GitHub says nothing.**
  Not a size error: the file stops being readable, every push yields a run with ZERO jobs titled
  after the file PATH, `name:` is never read, and it reads as an ordinary red run. It kills the
  workflow for everyone, not just the author — #3135 blocked every contributor's deploy until it
  was reverted (#3139). Ceiling measured by bisecting pushes against GitHub: **20054 chars
  accepted, 20654 rejected**, per STEP not per file (a 95295-byte control parsed fine while no
  single step crossed). The first attempt died of PROSE: 3240 characters of added comments took
  one step from 17414 to 20654; the same logic re-landed at +318 with the reasoning moved into
  script headers. Nothing here sees this class — PyYAML, a strict duplicate-key loader, actionlint
  and yamllint were all clean on the very file GitHub refused — so
  `check-workflow-run-step-size.py` (gate `workflow-run-step-size`) now enforces 19000. Rule:
  **prose belongs in `.github/scripts/*` headers, never in a `run:` block.**
- **Validate a workflow against GITHUB, not against a YAML parser — the oracle is free.** On a
  non-main branch a VALID workflow with `branches: [main]` produces NO run at all; an INVALID one
  still produces a failed run, because GitHub cannot apply a filter it could not parse. Push the
  candidate to a throwaway branch and count runs: zero means accepted. Validate the oracle both
  ways first (a known-bad file MUST produce a run), then bisect with it — that is how the 20054/
  20654 boundary above was found, in ten pushes, after local tooling had said "clean" three times.
  Re-run it after any rebase: "it parsed an hour ago on a different base" is a different claim.
- **`gitleaks` matches the SHAPE `curl -u "$USER:$PASS"`, and it scans the push RANGE, not the
  tip.** The rule `curl-auth-user` cannot tell a variable reference from a literal, so a NEW
  occurrence fails the required check even with no secret in it — the identical inline form
  already in auto-deploy.yml survives only because it predates the scanned diff. Assemble the pair
  into a variable first. And a fixup commit does NOT clear it: gitleaks reported "2 commits
  scanned, leaks found: 1" against the already-fixed tip, so the offending commit has to leave the
  branch history (squash to one commit, force-push with lease, re-sign).
- **A `concurrency.group` that interpolates a LIST silently stops the job being created once the
  list is big enough — and an absent job cannot honour its own `if:`.** `auto-deploy.yml`'s
  `gitops-pr` keyed its group on `needs.changes.outputs.services` verbatim. A change under
  `openbank-libs-*` rebuilds the whole fleet, so that expanded to ~1436 characters, and the job was
  then **never instantiated**: not skipped, absent — no job, no check-run on the commit, and
  `needs.gitops-pr.result` reading as a failure downstream. That job carries
  `if: always() && … != 'cancelled'` precisely so a partial `can-i-deploy` failure still deploys
  the
  subset that passed (#846), so the whole fleet build was built, pushed, signed, attested and then
  discarded — worst in the highest-stakes case. Correlation over 20 runs was exact: 53 services
  (~1436 chars) → job absent (three separate times); 12 (~322) and 1 (~30) → created. Key the
  group
  on a short digest of the **sorted** set (`jq -S -c 'sort' | sha256sum | cut -c1-12`), which keeps
  the same-set/disjoint-set semantics the list was there for and bounds the name at ~50 chars
  (#3082, fixed #3084). Generalize: anything interpolated into a `concurrency.group` must be O(1) in
  the size of the fleet — a group name is not a place to carry data.
- **A gate that calls a network API must be falsified with the CREDENTIAL CI will use, not the
  one on your laptop — and on GitHub a 404 does NOT mean "gone".** A job's `GITHUB_TOKEN` is
  scoped to this repository, so a **private** repo in the same account answers `404` byte for
  byte like a deleted one; `gh` on the owner's machine sees it fine. `check-stale-comment-
  references.py` shipped a rule reading 404 as "this repo no longer exists", passed every local
  run, and went red in CI on `JiRaska/openbank-app` — private, alive, correctly referenced. There
  is no API field that separates the two, so that half of the rule was **dropped** rather than
  tuned; only `archived` is claimed, since observing it requires read access and is therefore
  unambiguous. Generalize: before asserting anything from an API response, ask which identity the
  gate runs as and what that identity *cannot see* — a permission-shaped absence is
  indistinguishable from a real one, and it always fails in the direction of a confident wrong
  answer. The repo already knows "a gate that has only ever passed is unfalsified"; the sharper
  form is that a gate falsified under the **wrong identity** is unfalsified too.
- **Test the script EXTRACTED from the workflow, not a retyped copy of it.** A transcription is
  a different program: the first harness for #2890 used `[ … ] && { … }` where the real step
  used `if` blocks, and under `set -e` those differ on exactly the passing case. Parse the YAML
  and dump `.jobs.<job>.steps[<n>].run` to a file, then run that. When the step calls something
  destructive (`gh run rerun`, a deploy, a delete), stub the binary on `PATH` — **and validate
  the stub against a known-positive first**, or a silent passthrough runs the real thing.


### CI / bot commit signing

- **What signs a bot commit is the *endpoint*, not the token — and `main-protection` enforces
  `required_signatures`.** GitHub auto-signs the GraphQL `createCommitOnBranch` mutation and the
  Contents API, and only for a GitHub **App** token — never for a user PAT, and never for the Git
  Data API (`POST /git/commits`), whose `signature` field is caller-supplied. So
  `peter-evans/create-pull-request`'s `sign-commits: true` signs *once given an App token* (#1276),
  while **release-please can never sign whatever token you give it**: it delegates to
  `code-suggester`, which calls `octokit.git.createCommit` (Git Data) with no signer (upstream
  release-please-action#1171/#1124, both open; #1289 re-signs the commit afterwards instead).
  Unsigned + `required_signatures` = a PR stranded with green checks and auto-merge armed, which
  reads as healthy from every angle except the one nobody checks. Verify the *branch* commit —
  never the squash commit on `main`, which GitHub signs itself and always reads `verified=true`,
  proving nothing: `gh api repos/<owner>/<repo>/pulls/<N>/commits --jq '.[].commit.verification'`.
- **A guard that `setFailed`s must go where a failure costs nothing downstream.** Fail the *last*
  step of a job with nothing after it (`always()`, since `setFailed` skips later non-`always()`
  steps); if the job has dependents (`needs:`), make the guard its own job instead — failing in
  place would skip them. In `release-please.yml` that would have stripped an already-cut tag of its
  evidence bundle.
- **`git rebase` drops the signature off EVERY rebased commit, and `--amend -S` only fixes the
  tip.** A 3-commit branch rebased and re-signed with `--amend` still strands on
  `required_signatures` with two unsigned commits behind the tip. Re-sign the whole range:
  `GIT_SEQUENCE_EDITOR=true git rebase -i --exec 'git commit --amend --no-edit -S' origin/main`,
  then verify via the API (`gh api .../pulls/<N>/commits --jq '.[].commit.verification'`) — not
  `gh pr view`. **Prevent it up front:** `git config --global commit.gpgsign true`, so every
  `rebase`/`amend`/`commit` signs automatically and the range never desyncs. **Diagnosing the
  stranded PR:** the symptom is green checks + `autoMerge=true` but `mergeStateStatus=BLOCKED` and an
  empty `reviewDecision`; `gh api repos/<owner>/<repo>/branches/main/protection` returns `404 Branch
  not protected` — that is expected (the gate is the *ruleset* `required_signatures`, not classic
  protection), not a reason to reach for `--admin`. Confirm the tip's signature with
  `git log -1 --format='%G?'` (`N` = unsigned) before pushing.
- **There is no `merge_group:` trigger anywhere in the required-check workflows — and there cannot
  be one.** GitHub merge queue requires an **organization-owned** repo; this one is owned by a
  personal account, so adding a `merge_queue` rule to the ruleset returns `422 Invalid rule
  'merge_queue'` (issue #1465 has the isolation proof). Support for it was built (#1467), then
  deliberately **reverted** (#1504): an unreachable trigger is not a harmless spare part — nothing
  exercises `services-ci`'s `merge_group` base-selection logic, so it would silently drift from the
  PR-path logic it must mirror, and the day someone enabled a queue it would report stale-but-green,
  the exact vacuous-green failure class this codebase works hard to avoid elsewhere. Don't re-add it
  without re-checking the 422 first. Consequence: the frozen-`base.sha` race (#481 × #524) stays
  open, and `strict_required_status_checks_policy` (require branches up to date) is the only
  remaining lever that works on a personal account.


### Self-hosted runners share the machine with a human

- **A CI job that leaves `GRADLE_USER_HOME` unset does not merely *share* the workstation's
  `~/.gradle` — it PRUNES it.** `gradle/actions/setup-gradle` runs `cache-cleanup: on-success` by
  default: "remove any stale/unused entries from the Gradle User Home", where *unused* means
  "unused by this one CI build". Pointed at a developer's home it deletes artifacts local builds
  depend on and truncates files a concurrent local build is reading, so the local build reports
  `Dependency verification failed … expected X but was Y` for an artifact whose cached bytes match
  `verification-metadata.xml` and Maven Central **exactly**. That reads as cache corruption, and no
  amount of cache repair fixes it because the damage recurs on the next CI job — the diagnosis only
  closes when you notice the failures track the runner being busy. Every Gradle job that can land on
  a self-hosted runner needs its own home; `_service-ci.yml` resolves one per service in a step
  (a `workflow_call` job cannot reference the `env` context in a job-level `env:` block).
  `.github/scripts/check-gradle-user-home-isolation.py` enforces it in `Validate manifests`.


### Dependency graph & PR-time CVE gating

- **`dependency-submission.yml` MUST keep its `pull_request` trigger.** `dependency-review` only
  diffs *submitted* graphs and GitHub does not parse Gradle natively, so deleting that trigger as
  "redundant CI" leaves `block_on_cve_severity` green while checking nothing (#1421).
- **`retry-on-snapshot-warnings` retries even when no submission is coming** (GitHub always answers
  `No snapshots were found for the head SHA`) — armed unconditionally it burnt >11 min on PRs
  touching no manifest. Arm it only on dependency-touching PRs, and size the window from the
  measured ~24 min end-to-end, not the 734 s resolve (the documented 600 s cannot work here).
- **Touching ANY `build.gradle.kts` — even to add a comment — costs a ~11 min fleet resolution.**
  Both `dependency-submission.yml` and `dependency-review.yml` filter on `**/build.gradle.kts`,
  and a path filter cannot inspect intent: a comment-only edit changes no dependency yet arms the
  whole submit-and-wait path. So a drive-by comment in a service's build file is not free, and
  batching a real manifest change with unrelated formatting costs nothing extra — but doing the
  reverse (splitting a comment tweak into its own PR) buys an 11 min job for zero information.
- **A red push-triggered workflow on `main` is addressed to nobody.** `dependency-submission` died
  of `Java heap space` for three days, red every run, while Dependabot and the gate quietly read the
  stale graph — neither goes red when it is stale. Anything whose *output* others depend on needs an
  escalation path (raise/refresh an issue, as `fleet-attestation.yml` and this one now do). Its heap
  is a ratchet with nothing measuring it — expect to raise it again.
- **Fresh-cache builds fail dependency-verification on artifacts nobody has hashed yet — fix
  scoped, not by blanket regen.** `./gradlew <tasks> --write-verification-metadata sha256`
  rewrites the whole file: reorder noise, unrelated components, and (for test-classpath graphs)
  dozens of entries. The house norm (#2718, #2743) is additions-only — compute sha256 from the
  isolated cache and insert just the missing components with `origin="Maven Central"`. Symptom:
  `Dependency verification failed for configuration ...` on a cold `GRADLE_USER_HOME` (new runner,
  isolated cache, CodeQL's tracing build).
