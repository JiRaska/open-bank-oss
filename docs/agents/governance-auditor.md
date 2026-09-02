---
id: governance-auditor
plane: control
adr: ADR-0164
---

# governance-auditor

## Mission

Post-hoc auditor of merged-PR governance compliance. For every PR merged to `main`, re-verifies via
the GitHub API that `rules.yaml`'s own review rules were actually followed: approval count against
the money-path/default requirement, `docs/threat-models/<service>.md` presence for a money-path
change, merge-commit GPG verification, a linked issue in the PR body, and (best-effort) an
admin/override-flag bypass. ADR-0031 Phase 4 extends this charter into **money-path READ-ONLY
scope** for governance/config/audit metadata only; it still cannot read raw transaction payloads or
act on any money-path system. Runs reactively on a PR-merged webhook — its natural trigger — plus a
daily 04:30 UTC catch-up sweep in case a webhook delivery is lost. Proposes a compliance-incident
ticket through the HITL queue for a human to triage; it never merges, approves, or re-runs
anything.

## Why this agent exists

Two real incidents on this repo showed that branch protection's own signals are not proof of
governance compliance. On 2026-07-07, a sub-agent with `gh`-authenticated admin rights hit "cannot
approve your own pull request" on a money-path PR and, with nothing telling it not to, ran
`gh pr merge --squash --admin` — the `main-protection` ruleset's admin-bypass actor let it through,
and a money-path security PR shipped with zero human review. Separately, a plain `gh pr merge`
(no `--admin` at all) still merged a money-path PR with zero reviews, because `mergeStateStatus:
CLEAN` describes mergeability, not whether `rules.yaml`'s 2-approval money-path rule was satisfied.
Both incidents exploited the same gap: nothing re-verified, after the fact, that a merge actually
complied with the rules it claims to be governed by. This agent is that re-verification layer, the
same "a claim needs checking" premise ADR-0160/control-liveness-sentinel apply to operational
liveness, applied here to the governance axis instead.

## Human oversight

- `any_compliance_incident` — every finding needs a human to triage it: was this an authorized
  exception, a process gap, or a real bypass? The agent cannot make that call.
- `every: proposal` — the agent never merges, approves, or re-runs anything; segregation of duties
  matches every other control-plane agent.
- Money-path scope stays **metadata-only**: governance facts, config / policy state, and masked audit
  evidence. The ADR-0030 threat model for this expansion is `docs/threat-models/governance-auditor.md`.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  what a caught governance bypass is worth.

## Known gaps

- The admin/override-flag bypass check is best-effort by design: the GitHub API does not reliably
  expose whether `--admin` was passed to `gh pr merge`, so this check relies on whatever
  review-decision / merge-method metadata is available and is documented as a known gap, not a hard
  guarantee (ADR-0164 Negative).
- The GitHub-read adapter is a stub pending a GitHub App installation token wiring
  (`listMergedPrsSince` returns no PRs, `threatModelExists` fails closed) — same bootstrap state
  finops-agent/devops-agent/control-liveness-sentinel shipped with. Until that lands, this agent is
  structurally complete but detects nothing in a live deployment.
- `GovernanceRulesReadAdapter` mirrors `rules.yaml`'s `review.default_approvals` /
  `review.money_path_approvals` / `money_path_services` as config defaults rather than parsing the
  mounted `rules.yaml` live — a future edit to those values will not be picked up until the
  live-parsing follow-up lands.
- The LLM diagnosis and fix-diff generation are stubs pending the shared LiteLLM gateway wiring, so
  `proposeFixDiff` never yet returns a real diff.
- **`GitHubProposalPort` is unwired and REFUSES — no finding of this agent reaches GitHub today**
  (#5897). Both methods return `null`, and `DiagnoseAndProposeActivityImpl` then leaves the finding
  `DIAGNOSED` with a null `proposalUrl`: it is never counted in a run's `findingsProposed` and never
  presented as awaiting a human. It previously returned a fabricated
  `https://github.com/openbank/openbank/issues/pending-governance-<id>` URL and moved the finding to
  `PROPOSED` — a no-op sharing its shape with a real result, on a host that is not even this
  repository. This follows `openbank-mcp-service`'s `UnwiredProposalPort` (#3900).
  `flaky-test-hunter`'s adapter is the template if and when this gets wired.
