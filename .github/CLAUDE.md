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
- **Run `run-gates.py --all` before pushing a gate change — `--only <mine>` and `--group <my shard>`
  are structurally blind to the meta-gates.** A new `gates.yaml` entry is itself a SUBJECT of the
  gates that check gates, and those live in the `lint` shard: `gate-lifecycle-metadata` wants
  `rationale:` + `review_after:`, `gate-subject-floor` (#4339) wants `min_subjects:` **and** the gate
  printing `SUBJECTS=<n>`, `gate-selftest-declaration` wants `selftest:` or a `selftest_exempt:` with
  a category from the closed vocabulary. So a gate landing in any other shard can be green in every
  local run its author thinks to make and red in CI — measured on #4807, where `--only` + `--group
  supplychain` both passed and `gates (lint)` failed on two of the three. Both meta-gates had landed
  on `main` while the branch was open, which is the normal case, not bad luck. The full run is ~340 s
  CPU / ~50 s wall on 8 jobs; that is cheaper than one CI round trip.
  **Six failures are environmental — know them or you will read them as your regression.** Five
  diff-scoped gates (`api-contract`, `release-scope-mismatch`, `db-migration`, `schema-compat`,
  `threat-model-updated-on-trust-boundary-change`) print `PR_DIFF_BASE is empty but this gate
  requires it — refusing to run vacuously`, and `loki-rule-load-test` reports UNFALSIFIED on
  `BASE_REF: unbound variable`. Both are variables only CI sets. Confirm rather than assume: run the
  same ids in a throwaway `git worktree add --detach <tmp> origin/main` and check they fail
  identically there. Anything that fails on your branch and passes on that control IS yours.
- **A gate that has only ever passed is unfalsified.** Its failure path is code nobody has run, and
  it fails in ways a green/red signal cannot express. Three independent instances in one week: the
  ADR-0071 governance reporter crashed with a `TypeError` on *every* failure, so it had never once
  printed a gap (#2165); a `Trivy fs scan` step exited 1 while printing no finding, so the actual CVE
  had to be read out of the code-scanning API instead (PR #2154); an OOM'd `fleet-lint` reported a
  plain red while having silently left half the fleet unlinted — 455 actionable findings against a
  true 920 (#2177). Feed every new gate an input it MUST flag, and read what it *prints*, not just
  its exit code.
- **A rate-limited SARIF UPLOAD loses a real finding and reads as a scan failure.** On 2026-09-05
  the installation limit hit CodeQL and Trivy in the same hour, and in both the analysis RAN — the
  log shows queries interpreted and `Exported results to SARIF` — before
  `##[error]API rate limit exceeded for installation` on the upload step. So the check goes red
  having found nothing anyone can read, and a genuine alert in that SARIF is simply gone. Two
  consequences worth carrying: a red security check is not evidence of a finding OR of cleanliness
  until you read which STEP failed, and a green run earlier in the same hour does not clear a later
  red one. The same hour's `dependency-review` failed with
  `Dependency review is not supported on this repository. Please ensure that Dependency graph is
  enabled` — which is NOT what it means when the graph is on (measured: the `dependency-graph/sbom`
  endpoint answered with 2373 packages, and the same workflow passed on other branches minutes
  later). That message is what the action prints when its API call fails for any reason, so it sends
  you into the repository settings for a problem that is not there.
- **"I could not READ the corpus" is a third state, and a gate that renders it as a failure
  turns someone else's rate limit into your red PR.** On 2026-08-21 ~18:25 UTC one installation
  rate limit hit two gates in the same run and they disagreed:
  `check-stale-comment-references.py` printed `::notice:: … UNRESOLVED … Not a pass and not a
  failure` and stayed green, while `ruleset-context-parity` exited 1 and reddened #5896 — a PR
  touching only admin-ui, `openbank-infra/scripts`, docs and a `CLAUDE.md`. "The ruleset requires
  X and no job emits X" and "the rulesets API did not answer" are different facts; only the first
  is a finding. The pattern to copy is the notice + exit 0, with the transient family named
  explicitly (rate limit, secondary rate limit, timeout, DNS/connection, 5xx) so a NON-transient
  failure still goes red — on GitHub a rate limit and a permission denial are both HTTP 403 and
  are told apart only by the message text (`API rate limit exceeded` vs `Resource not accessible
  by integration` / `Must have admin rights`), so distinguish on that, and where a probe cannot
  tell them apart, say so rather than degrading both. **The floor undoes this one layer up if you
  let it**: `min_subjects:` sees 0 subjects and fails a run that examined nothing by definition,
  so the gate prints `SUBJECTS=UNRESOLVED` (`gatelib.subjects_unresolved`) and run-gates.py skips
  the floor for that run only, saying so in the output — a silent pass there is indistinguishable
  from a gate that really looked.

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
- **A gate over `application.yaml` says nothing about the gitops env that overrides it — and moving
  a URL into gitops moves it OUT of scope.** `incluster-hostname-resolution` reads
  `openbank-*/src/main/resources/application.yaml` only. So the fleet-standard fix for a bad host
  (localhost dev default + real URL in the workload env) hands the checked claim to an unchecked
  file. Not hypothetical: vop-service's ADR-0171 payee-name hop declares
  `PARTY_SERVICE_URL: http://party-service.parties.svc:8100` in `payments-services.yaml` — namespace
  `parties` does not exist (it is `party`) and 8100 is account-service's port — alongside
  `ACCOUNT_SERVICE_URL: …accounts.svc:8101`, where 8101 is ledger's. Both ports transposed, live on
  the pod, invisible to the gate. When a gate's subject can be overridden, either extend it to the
  overriding layer or say in its own header which layer it does not cover.
- **Evidence cannot corroborate the layer it is DERIVED FROM — extending a gate to that layer
  silently makes it vacuous, and it still reads as green.** `check-incluster-hostnames.py` widens
  its known-good set with every host a gitops workload env dials ("if the deployment manifest
  dials it, the platform believes it exists"), which is sound while the CLAIM comes from
  `application.yaml` and the CORROBORATION from gitops — two independently-authored places. Point
  the same gate at the gitops env and that becomes the same statement twice: every host vouches
  for itself, nothing can ever be flagged, and the output still says OK. The fix is not more
  cleverness but a parameter — the caller passes what it accepts as existing, so the two layers
  cannot share a believed-set by accident, and a self-test asserts a corroborated-only host stays
  clean in one layer and IS flagged in the other (#3966). **Before reusing any "known-good" set on
  a new input, ask what that set is derived from**; the same shape appears wherever a baseline, an
  allow-list or a cache is built from the artifact it is about.
- **The derived alternative to a hand-kept list is not automatically better — measure it against
  the known cases before preferring it on principle.** This repo rightly distrusts hand-kept lists
  (a gate whose scope is one reads as PASSING when the list is short). So the instinct for the
  above was a derived rule: believe a host that ≥2 distinct workloads dial. Measured against the
  real tree it was wrong in BOTH directions at once — it would have believed two live defects
  (`tpp-registry-service.tpp.svc` and `sepa-payment-service.payments.svc`, each dialled twice) and
  flagged three real Helm-provisioned Services dialled only once. A 10-entry list, each entry
  verified with `kubectl` and **checked both ways** so a stale one fails, beat it outright. The
  rule that survives is narrower than "never hand-keep a list": never let a gate's SCOPE be
  hand-kept, because a short list then reads as full coverage — but a hand-kept list of external
  FACTS is fine when the gate fails on a stale entry, since it can only shrink by being noticed.
- **A comment that explains away a symptom is worse than no comment — it retires the question.**
  psd2's manifest annotated its TPP-registry URL "Not yet deployed -> calls 503 until it lands".
  The service was deployed and serving; the URL named a namespace that has never existed. Anyone
  who noticed the failing lookup found it already accounted for, so the note survived as long as
  the bug did. Same family as the stale-prose rule under ktlint/detekt above, but the failure is
  worse: stale prose merely misinforms, whereas an explanation of a symptom suppresses the
  investigation. When you write one, name what you VERIFIED and when — and when you fix a defect
  whose comment predicted it, correct the comment in place rather than deleting it, so the next
  reader learns the note was wrong rather than that it was never there.
- **An advisory check over a *generated* artifact is a contradiction.** On a hand-written artifact a
  red advisory check means "someone should look"; on a generated one it means "the committed document
  does not match reality" — there is no judgement left to exercise, so advisory just makes the drift
  mergeable. `eu-ai-act-registry` went red twice on #2156 and the PR merged anyway, leaving the EU AI
  Act inventory omitting an AI system until it was regenerated (#2216).
- **A third-party URL in `application.yaml` is unfalsifiable by every test layer this repo has —
  only fetching it can be wrong out loud.** A unit test stubs the client, an IT serves a local
  fixture, and a consumer pact answers whatever path it is asked for (the #2283 asymmetry). So
  `openbank-fx-service`'s ČNB fixing URL was a 404 — one path segment short, `kurzy-devizoveho-trhu`
  appears TWICE — for the entire life of the service, and every layer stayed green: a 404 IS a valid
  HTTP response, so the rest-client succeeded and the circuit breaker never opened; ČNB serves it as
  a 58 KB HTML page, the parser rejected that, and the scheduler's `catch` swallowed it into one
  ERROR line. Downstream, `FxRevaluationService` found no valid rate, logged "skipping its
  revaluation leg" and returned `posted = false` — a successful-looking run of a job that revalued
  nothing. The only evidence anywhere was a table that stopped growing, and **nothing alerts on a
  table not growing**. Two transferable rules. (a) **Probe the payload's SHAPE, never the status
  code** — a 200 proves a server answered, not that the answer is the feed. (b) **The probe must
  read the URL out of the committed config, never keep its own copy** — a second copy moves with the
  first and keeps passing against a URL the service does not use. `check-external-feeds.py` +
  `external-feed-watch.yml` do both (drift half enforced in `Validate manifests`, liveness half
  daily and escalating, never merge-blocking). Its first run found a *second* dead ČNB URL,
  customer-edge's bank registry, silently masked by an embedded fallback (#2204).
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
- **Regenerating the OPA bundles must be the LAST step before pushing a `rules.yaml` PR, and the
  "run twice, expect no diff" check does not prove you did that.** Idempotency only proves the
  generators are stable against whatever `rules.yaml` said at that moment: on #2457 the bundles were
  regenerated, verified idempotent, and *then* a duplicate-key fix landed in `rules.yaml` — all 65
  files embedded the superseded text and the OPA gate went red on every one. Re-run after the final
  edit, not after the first.
- **`rules.yaml` is never linted by CI** — the yamllint step's scope is `openbank-infra .github`. A
  duplicate key there is silently resolved by SnakeYAML keeping the LAST occurrence, so a new value
  added above an existing one is dropped with no error anywhere. Diff yamllint finding *sets*
  against `origin/main` when editing it (that is the only thing that caught it on #2457).
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
  **The checker that enforces this had the same blind spot as every other probe here: it keyed
  on `check-*.py|sh` in the step's `run:`, so the ADR-0071 governance-manifest gate — plain
  `node scripts/generate-governance.mjs` — was invisible to it and sat in that same `ui-build`
  job while the checker reported it clean** (#4083, the third sighting after #2236 and #3629).
  A gate is not always a `check-*` script; the one thing it always has is a NAME saying it is
  one, so `check-gate-invocation-reachability.py` now also flags a step whose name contains
  `gate`/`enforced`/`advisory` in a narrowing job, with `always()` carved out explicitly
  because it broadens rather than narrows. Falsified by running it against pre-fix `ci.yml`.
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
- **"Nothing reads this file" is a claim about the whole repo, not about the pipeline — grep
  before you delete a declaration.** The per-service `openbank-*/Dockerfile` files are documented
  as declaration-only with `EXPOSE` the single live field (#3016), so the tidy fix for a stale
  `FROM` looks like deleting it. It is not:
  `openbank-admin-ui/scripts/generate-cluster-topology.mjs` parses
  `openbank-ledger-service/Dockerfile` in `imageFacts()` and renders the base image into the
  /docs/cluster dossier (ADR-0081) — and when it cannot parse one it falls back to a HARDCODED
  `eclipse-temurin:25-jre-alpine` literal, so removing the declaration would have resurrected the
  fiction in the UI instead of retiring it (#3354, #3630). A second reader that is a *generator
  in another tree* is invisible from the file, from the deploy pipeline, and from the gate that
  owns the file's shape.
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
- **`gh pr merge` refuses on `mergeStateStatus=UNSTABLE` even when every REQUIRED check is green.**
  A non-required check that is red (here `CodeQL (java-kotlin, manual)`, failing with "could not
  process any code written in Java/Kotlin" on a PR that touches only a workflow YAML — there is no
  Java to analyse) leaves the PR mergeable by the ruleset but unstable to `gh`, which then suggests
  `--admin`. Do NOT reach for it: `--squash --auto` is the documented non-override path and merges
  as soon as the required contexts pass. On this repo that is immediate, since
  `required_approving_review_count: 0` — it returns with `autoMergeRequest` null and the PR
  already
  MERGED, which reads like it did nothing.
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
- **Validate the PROBE, not the command inside it — in zsh a `for x in $VAR` loop runs ONCE.**
  zsh does not word-split an unquoted parameter (bash does), so a sweep written as
  `LIST="a b c"; for b in $LIST; do git show-ref --verify --quiet "refs/heads/$b" …` tests one
  ref literally named `a b c`, finds nothing, and reports the estate clean. Measured
  2026-07-31 on a leftover-branch sweep: it printed `0 of 17` while five refs were sitting
  there. Use `${=VAR}`, a real array `VAR=(a b c)`, or `while read` from a heredoc — the last
  is safest since it also survives names with spaces.
  The transferable half is the failure of the check on the check: `git show-ref` *was* validated
  against a known-positive and passed, because the bug was in the LOOP, not the command. A
  component test is not a probe test. Feed the whole construct a case it must flag — here, a
  branch you know exists — and only then trust its silence.
- **Test the script EXTRACTED from the workflow, not a retyped copy of it.** A transcription is
  a different program: the first harness for #2890 used `[ … ] && { … }` where the real step
  used `if` blocks, and under `set -e` those differ on exactly the passing case. Parse the YAML
  and dump `.jobs.<job>.steps[<n>].run` to a file, then run that. When the step calls something
  destructive (`gh run rerun`, a deploy, a delete), stub the binary on `PATH` — **and validate
  the stub against a known-positive first**, or a silent passthrough runs the real thing.

### main-protection: the bypass has two halves, and only one had a reader
- **`rulesets/rule-suites` (the bypass LOG) is private and rejects fine-grained tokens;
  `rulesets/{id}` (the bypass CONFIG) is world-readable.** Same feature, opposite access, and it
  decides what kind of control each half can carry. The log needs a **classic** PAT with `repo`
  (or a GitHub App token with Administration:read) — a fine-grained token holding
  `Read access to administration and metadata` still answers `403 Resource not accessible by
  personal access token`, and the 403 names no permission, so it reads like a scope problem
  forever (#4791). A workflow `GITHUB_TOKEN` can never read it: `permissions:` has no
  `administration:` key, and declaring one makes GitHub refuse to parse the whole workflow (zero
  jobs, every push). Because of that the log half can only ever be a scheduled watch
  (`merged-past-red-check-watch.yml`, #4240) — never a required check. The config half needs no
  token at all and therefore can be one (`ruleset-bypass-actors`, Refs #4828).
- **Detection and prevention are different halves, and the fleet had only detection.** The watch
  names a merge that already went past a failing required check, up to twelve hours late. Who is
  *permitted* to do that is one field, `bypass_actors` — today a single entry, RepositoryRole#5
  (`admin`), `bypass_mode: pull_request`. Nothing in CI can stop that override: GitHub evaluates
  the bypass at merge time, after every check has reported. What CI *can* do is notice the surface
  being widened — a bot, a team, or an existing entry flipped to `always` (which additionally
  permits a **direct push** to `main`) — which otherwise produces no diff, no PR and no red check
  anywhere. Widening it is a Settings edit; after the gate it is a reviewed commit.
- **`bypass_mode` must be compared, not just the actor id.** `pull_request` -> `always` keeps the
  same `actor_id` and is the difference between "can merge a PR past a red check" and "can push
  straight to main". A comparison keyed on the id alone calls that clean.
- **Every agent here runs as an identity that CAN bypass.** `current_user_can_bypass` reads
  `pull_requests_only`, not `never`, for the owner credentials `gh` is authenticated with — which
  is why the prompt-level prohibition on override flags is load-bearing rather than belt-and-braces.

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
Rationale + what does *not* cover it: `rules.yaml: dependencies.pr_time_cve_gate` (authoritative).
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

