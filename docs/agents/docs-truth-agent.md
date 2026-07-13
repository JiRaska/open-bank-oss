---
id: docs-truth-agent
plane: control
adr: ADR-0166
---

# docs-truth-agent

## Mission

Periodic ADR-status-vs-code drift detector. On a weekly 06:00 UTC sweep plus reactively whenever
any `docs/adr/*.md` file changes, this agent greps every ADR's `Delivery-Status:` line against the
code/service/class/config key it names: a `Shipped`/`Complete` ADR whose named artifact cannot be
found or isn't wired up, a `Planned`/`Partial` ADR whose fully-built implementation already exists
unmentioned in the ADR text, and a mismatch between an ADR's or doc's "enforced" claim and
`rules.yaml`'s own gate-graduation `enforced:` flag. It correlates all three into one triaged
report rather than leaving ADR staleness as a silent gap nobody watches proactively. Findings are
almost always a tracking ticket — correcting an ADR's substantive content is a human judgment call
this agent should not make — with a rare mechanical PR reserved for the one unambiguous case:
flipping just the `Delivery-Status:` line, nothing else in the file. It never edits an ADR's
decision content, merges a PR, or writes to `rules.yaml`.

## Why this agent exists

Two real incidents on this repo show that an ADR's own standing claims can silently drift from the
code they describe, and nothing was watching that specific relationship. ADR-0139 and ADR-0140
both carried a status line describing a feature store as not yet implemented, while the feature
store had, in fact, already shipped as `OnlineFeatureStore` — nobody caught the drift until a
duplicate feature store was designed and partially built against the stale claim, and that
duplicate work had to be reverted. Separately, an earlier ADR's own scheduler doc-comment claimed
a downstream payment-rail consumer existed for a published event; nothing consumed that event for
weeks before the gap was found. Both incidents are failures of the same shape control-liveness-
sentinel's own foundation already diagnosed for *runtime* behaviour — a standing claim is not proof
it still holds — applied here to *documentation* instead: an ADR's `Delivery-Status:` line, or its
prose about what wiring exists, is a claim like any other, and nothing previously re-verified it
against the repo on any cadence. This agent is the periodic, grep-based re-verification layer for
exactly that relationship — narrower and more static-analysis-flavored than a runtime watchdog, but
closing a gap none of this repo's other control-plane agents cover.

## Human oversight

- `any_adr_status_correction` — every finding needs a human who reads both the ADR and the current
  code to judge the correct status; the agent cannot make that call for two of its three checks.
- `every: proposal` — the agent never merges a PR, edits an ADR's decision content, or writes to
  `rules.yaml`; segregation of duties matches every other control-plane agent.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  the cost of duplicate work built against a stale ADR claim.

## Known gaps

- The `RepoScanPort`/`GovernanceRulesPort` adapters are genuine (not stubbed) grep/text-scan
  implementations, but deliberately shallow — existence and basic wiring, not deep semantic
  verification. A renamed class that still does the same job can look "missing," and a
  same-named artifact from an unrelated context can look like a false match; a `draft.ticket` gives
  a human the citation, not a verdict.
- The artifact-extraction and enforcement-claim heuristics (backtick-quoted tokens, "not yet
  built"-style phrase proximity, "enforced"/"advisory" word proximity in `rules.yaml`) are
  best-effort text scans, not a real Markdown/YAML semantic parse — a future ADR-authoring
  convention change could reduce their match rate and would need a corresponding update here.
- The `LlmDiagnosisPort` and `GitHubProposalPort` adapters are stubs pending the shared LiteLLM
  gateway and GitHub App installation-token wiring, the same bootstrap state finops-agent/devops-
  agent/control-liveness-sentinel/governance-auditor/release-steward shipped with. Until that
  lands, a finding produces a tracking ticket with a placeholder summary rather than an
  LLM-drafted root-cause note, and `proposeFixDiff` never yet returns a real diff.
- `repo-root` (`DOCS_TRUTH_AGENT_REPO_ROOT`) must point at a mounted, up-to-date checkout of `main`
  for the `RepoScanPort`/`GovernanceRulesPort` checks to be meaningful — the deployment-side
  checkout-mount wiring (a sidecar or init-container `git pull`) is tracked separately and not yet
  part of this PR's gitops manifest.
