---
id: release-steward
plane: control
adr: ADR-0165
---

# release-steward

## Mission

Periodic guardian of the release-please / version-axis invariants. On a daily 05:00 UTC sweep plus
reactively on every release-please PR merge, this agent checks four things: `release-please-
config.json` vs `.release-please-manifest.json` lockstep (rule #3, ADR-0029), `openbank-admin-ui`'s
`package.json` version against `version.txt`, a fleet-wide scan for any service `application.yaml`
that sets `quarkus.application.version` explicitly, and an open-PR collision check across every PR
touching an `openapi.yaml` — comparing each PR's proposed `info.version` against `main`'s current
value and against every other open PR touching the same file. It correlates all four into one
triaged report rather than leaving them as separate silent CI gates nobody watches proactively.
Findings become a tracking ticket for the three judgment-call checks (a human picks the correct
manifest baseline, confirms which side of an admin-ui sync is authoritative, or decides which
racing PR re-bumps), and a scaffold PR for the one mechanically fixable case (deleting an explicit
`quarkus.application.version` key) — always through the HITL queue. It never edits `version.txt`,
merges a release-please PR, or writes to `openapi.yaml` directly.

## Why this agent exists

Four real incidents on this repo show that a per-PR, diff-scoped CI gate is blind to fleet-wide
release/version-axis state. A release-please merge silently dropped a manifest entry while it
stayed registered in `release-please-config.json`, which then failed the release-registration gate
on every other open PR and made release-please itself propose a regressed version for the affected
component. Separately, `openbank-admin-ui`'s `package.json` desynced from `version.txt` because
release-please's `extra-files` JSON updater is replace-based and silently stops updating once a
historical desync breaks its string match — the drift used to surface only at deploy time. A
`quarkus.application.version` override in a service `application.yaml` shadowed the build-stamped
`version.txt` value fleet-wide, requiring a 37-service drift sweep to fix — `check-app-version-
override.sh` now catches this in CI, but only reactively, on whichever service a given PR happens
to touch next. And two competing PRs both bumped the same `openapi.yaml:info.version` because the
api-contract gate classifies against its own PR's merge base, structurally blind to a concurrent
PR it never diffs against (PR #481 vs PR #524). This agent is the periodic, fleet-wide
re-verification layer for exactly these four invariants — the same "a passing check is not proof
the invariant holds repo-wide" premise its sibling control-liveness-sentinel and governance-auditor
agents apply to other axes, applied here to release engineering itself.

## Human oversight

- `any_release_or_version_drift` — every finding needs a human to pick the correct manifest
  baseline, confirm the authoritative side of an admin-ui version sync, or decide which racing PR
  re-bumps. The agent cannot make that call for three of its four checks.
- `every: proposal` — the agent never merges a release-please PR, edits `version.txt`, or writes to
  `openapi.yaml`; segregation of duties matches every other control-plane agent.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  the cost of a regressed release version or an undetected openapi collision.

## Known gaps

- The `RepoStateReadPort` adapter is a genuine (not stubbed) file-based re-derivation of three CI
  scripts' logic, but it is a best-effort mirror, not a shared implementation — a future change to
  `check-release-registration.py`, `check-admin-ui-version-sync.sh`, or `check-app-version-
  override.sh`'s logic needs a matching update here, or the two can silently disagree about what
  counts as a violation.
- The `GitHubOpenPrReadPort` adapter is a stub pending a GitHub App installation token wiring
  (`listOpenPrsTouchingOpenApi` returns no PRs, `mainOpenApiVersion` returns null) — same bootstrap
  state finops-agent/devops-agent/control-liveness-sentinel/governance-auditor shipped with. Until
  that lands, the openapi-collision check (ADR-0165 incident 4) is structurally complete but
  detects nothing in a live deployment.
- The LLM diagnosis and fix-diff generation are stubs pending the shared LiteLLM gateway wiring, so
  the one mechanical-fix path (`APP_VERSION_OVERRIDE`) does not yet generate a real diff.
- **`GitHubProposalPort` is unwired and REFUSES — no finding of this agent reaches GitHub today**
  (#5897). Both methods return `null`, and `DiagnoseAndProposeActivityImpl` then leaves the finding
  `DIAGNOSED` with a null `proposalUrl`: it is never counted in a run's `findingsProposed` and never
  presented as awaiting a human. It previously returned a fabricated
  `https://github.com/openbank/openbank/issues/pending-release-steward-<id>` URL and moved the
  finding to `PROPOSED` — a no-op sharing its shape with a real result, on a host that is not even
  this repository. This follows `openbank-mcp-service`'s `UnwiredProposalPort` (#3900).
  `flaky-test-hunter`'s adapter is the template if and when this gets wired.
- `repo-root` (`RELEASE_STEWARD_REPO_ROOT`) must point at a mounted, up-to-date checkout of `main`
  for the `RepoStateReadPort` checks to be meaningful — the deployment-side checkout-mount wiring
  (a sidecar or init-container `git pull`) is tracked separately and not yet part of this PR's
  gitops manifest.
