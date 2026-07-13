# ADR-0165 — Release-steward AI agent

Date: 2026-07-13
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska (paired with Claude Fable 5)

## Context

ADR-0029 makes release-please the single mechanism that turns a Conventional Commit into a
version bump, a changelog, and a git tag — CLAUDE.md rule #3 states "changelog & release are
automatic," and rule #2 forbids hand-editing `version.txt`. ADR-0048 layers a second, independent
version axis on top: the API contract (`openapi.yaml:info.version`), which must never be forced
equal to the release version. Both axes are enforced by CI gates, but this repo's own history
shows the gates are diff-scoped and per-PR — none of them looks at the *fleet* or at *all open
PRs at once*, and that blind spot has broken the release machinery itself, repeatedly:

1. **Manifest drift silently drops a component.** A release-please merge commit can reorder the
   tail of `.release-please-manifest.json`. When that happens, an entry can vanish from the
   manifest while it stays in `release-please-config.json`. The release-registration gate (rule
   #3; `openbank-infra/scripts/check-release-registration.py`) then fails on **every** open PR —
   because it runs against repo state, not the diff — and release-please itself loses its version
   baseline for that component and proposes a regressed version (observed: transaction-service
   `1.10.0` → a fresh `1.0.0`).
2. **admin-ui's `package.json` desyncs from `version.txt`.** admin-ui is release-type `simple`;
   release-please bumps `version.txt` as the primary version file and treats `package.json` as an
   `extra-files` JSON updater. That updater is replace-based — once a historical desync leaves
   `package.json` behind, release-please can no longer find the previous version string to
   replace, so `package.json` silently stays behind on every subsequent release. The drift used to
   surface only at deploy time (`build-push-admin-ui.sh` enforces the same invariant and the build
   fails), far downstream of the change that caused it.
3. **`quarkus.application.version` shadows the build-stamped version.** The convention plugin
   `openbank.quarkus-service` reads `version.txt` into the Gradle project version, and the Quarkus
   Gradle plugin propagates it into `quarkus.application.version` at build time. A service
   `application.yaml` that sets that key explicitly shadows it with a stale literal — most
   services that drifted this way sat frozen at `0.1.0`. This broke `rules.yaml: release_invariant`
   (`version.txt == quarkus.application.version == git tag`) fleet-wide and required a 37-service
   drift sweep (issues #2669/#2717) to fix. `check-app-version-override.sh` now enforces this in
   CI, but only reactively, per changed file, on whichever PR happens to touch that service next —
   a service nobody touches can carry the drift indefinitely without anyone noticing.
4. **Two racing PRs can both claim the same `openapi.yaml:info.version`.** The api-contract gate
   (ADR-0048 D5, `check-api-contract.py`) classifies a PR's OpenAPI diff against that PR's merge
   base. If a competing PR bumps the same spec's `info.version` and merges first, the second PR's
   gate never sees it — the diff base moved, but the running gate did not re-check against the
   *current* `main`. The ledger spec hit exactly this (PR #481 vs PR #524, corrected by #534): both
   PRs passed their own gate individually, and the collision was only caught by manual review of a
   3-dot diff.

Incidents 1–3 are the release/version axis; incident 4 is the API-contract axis (ADR-0048's
"two independent axes" framing). All four share the same failure shape ADR-0160 and ADR-0164
already diagnosed for other axes: **a per-PR, diff-scoped CI gate is blind to fleet-wide state and
to concurrent PRs it never diffs against — a passing gate is not proof the invariant actually
holds repo-wide.** Nothing periodically re-derives these four invariants from the current state of
`main` and the current set of open PRs; each is only checked when a PR happens to touch the
directly-relevant file, and the open-PR collision (incident 4) has no CI gate shape at all that
could catch it (a diff-scoped gate is structurally blind to a competing branch it isn't diffing
against).

## Decision

We add **release-steward** as a new control-plane AI agent (ADR-0031), a periodic guardian of the
release-please / version-axis invariants, following the same Temporal-orchestrated hexagonal shape
as finops-agent (ADR-0112), devops-agent (ADR-0119), control-liveness-sentinel (ADR-0163), and
governance-auditor (ADR-0164):

- **Reads only**, via direct repository-checkout reads (`release-please-config.json`,
  `.release-please-manifest.json`, `openbank-admin-ui/package.json` and `version.txt`, every
  service `application.yaml`) and the GitHub API (`github-prs-readonly`, open PRs touching any
  `openapi.yaml`).
- **Runs on a daily 05:00 UTC sweep plus reactively on every release-please PR merge** (this
  agent's natural trigger, the same way a PR-merge webhook is governance-auditor's) — a
  release-please merge is exactly when incidents 1 and 2 have historically been introduced, so
  checking right after one lands catches the drift before the next PR trips over it.
- **Checks four things per run, correlated into one triaged report rather than four separate
  silent CI gates nobody watches proactively**:
  1. **Manifest/config lockstep** — mirrors `check-release-registration.py`'s exact set logic:
     every `openbank-*/version.txt` module is registered in both `release-please-config.json` and
     `.release-please-manifest.json`, with no orphan on either side (incident 1).
  2. **admin-ui version sync** — `openbank-admin-ui/package.json:version` equals
     `openbank-admin-ui/version.txt` (incident 2).
  3. **Fleet-wide `quarkus.application.version` override scan** — mirrors
     `check-app-version-override.sh`'s logic across every released service's
     `application.yaml`, run proactively across the whole fleet rather than only on the one
     service a given PR happens to touch (incident 3). This is a proactive *duplicate* of an
     already-enforced CI gate, by design — the gap it closes is "nobody is watching a service
     that never gets touched," not "the gate is missing."
  4. **Open-PR `openapi.yaml:info.version` collision detection** — lists every open PR touching
     any `openapi.yaml`, extracts each PR's proposed `info.version`, and compares it against both
     `main`'s current value and every other open PR's proposed value for the same file, flagging a
     collision before either PR merges (incident 4). This is genuinely new capability: the
     existing CI gate is diff-base-blind to this race by construction (it only ever compares one
     PR's head against its own base); a periodic agent that queries all open PRs at once is not.
- **Proposes tickets by default, a mechanical fix PR only for the one case that has a
  deterministic single-line correction.** A manifest-drift finding (check 1) needs a human to pick
  the correct baseline version — it is a judgment call, not a mechanical diff — so it becomes
  `draft.ticket`. An admin-ui version-sync finding (check 2) likewise needs a human to confirm
  which side is authoritative. The `quarkus.application.version` override (check 3) is
  mechanically fixable — delete the offending key — so `openProposalPr` stays available in the
  port for that case, mirroring the CI fix `check-app-version-override.sh` already documents. An
  openapi collision (check 4) is always a ticket: which of the two racing PRs re-bumps is a human
  scheduling decision (whoever lands second takes the next version, per this repo's own convention
  note in CLAUDE.md), not something the agent should decide by picking a PR to edit.
- `tools.deny` blocks every write/execute tier explicitly, matching every sibling control-plane
  agent — this agent can never edit `version.txt`, merge a release-please PR, or push to
  `openapi.yaml` directly; it only reports and, for the one mechanical case, proposes a PR a human
  must still approve and merge.

## Alternatives considered

- **Rely entirely on the existing three CI gates plus manual 3-dot-diff review for the fourth.**
  Rejected because this is the status quo that produced all four incidents. `check-release-
  registration.py` and `check-app-version-override.sh` are correct and necessary, but both are
  diff/PR-scoped — they only run against whatever a given PR touches, and incident 1 shows the
  release-registration gate can be *tripped by a release-please merge itself*, at which point every
  other open PR starts failing it with no single owner watching for that fleet-wide state change.
  The openapi collision (incident 4) has no gate shape that could catch it at all under a
  per-PR-diff model.
- **Extend governance-auditor's charter instead of a new agent.** Rejected for the same
  least-privilege reasoning ADR-0164 used against folding into control-liveness-sentinel: this
  agent's read scope (local repo-checkout file reads plus `github-prs-readonly` open-PR queries)
  and its release-please-merge trigger are different enough from governance-auditor's
  merged-PR-compliance scope that a shared charter would blur the ADR-0031 D2 least-privilege
  boundary, and would make the audit trail (`governance_check_type_impacted` vs. a hypothetical
  `release_invariant_impacted`) ambiguous about which axis a finding belongs to.
- **A CI gate that runs on a schedule instead of an agent.** A scheduled GitHub Actions workflow
  could re-run the three mirrored scripts fleet-wide and list open PRs for the fourth check — this
  is a legitimate complementary hardening and not mutually exclusive with this agent. It was not
  chosen as the *sole* fix because a bare scheduled gate still just fails red with no triage: it
  does not diagnose *why* the manifest drifted, correlate it with the other three invariants in
  one place, or draft the human-facing ticket. The agent shape gives HITL triage and a single
  correlated report; a future scheduled-workflow variant of the same checks remains a reasonable
  belt-and-braces addition.

## Consequences

**Positive**
- Closes the "a passing per-PR gate does not mean the fleet-wide invariant holds" gap directly —
  the exact blind spot behind all four incidents in Context.
- Correlates four previously-siloed checks (three CI scripts, one no CI gate covers at all) into
  one triaged report, so an operator sees "release-please just dropped a manifest entry AND two
  open PRs are racing on the ledger spec" as one picture instead of three uncorrelated red checks.
- The openapi-collision check (item 4) is capability this repo did not have before at any layer —
  not a proactive duplicate of an existing gate, unlike checks 1–3.
- Same governance shape as its three siblings — no new review pattern for operators to learn, and
  `tools.deny` makes the "this agent cannot itself corrupt a release" guarantee structural.

**Negative**
- Detection, mostly not correction — three of the four checks route to a ticket, not a diff,
  because picking the correct manifest baseline or resolving a version race is a human judgment
  call this agent should not make unilaterally. Only the `quarkus.application.version` override
  has a deterministic mechanical fix.
- Proactive duplication of `check-release-registration.py` and `check-app-version-override.sh` is
  intentional (catching what a diff-scoped gate structurally cannot), but it does mean the same
  invariant is now expressed in three places (two CI scripts plus this agent's `RepoStateReadPort`
  mirror) — a future change to either script's logic needs to stay in sync with this agent's
  activity implementation, or the two can silently disagree about what counts as a violation.
- A fifth Temporal-orchestrated control-plane agent adds one more workload watching a governance
  surface finops-agent/devops-agent/control-liveness-sentinel/governance-auditor already read
  pieces of — the same acceptable, least-privilege-scoped duplication trend control-liveness-
  sentinel's and governance-auditor's ADRs already flagged, still worth tracking as the
  control-plane agent count keeps growing.

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101) and the existing GitHub-proposal / HITL-queue
  pattern; the repo-state-read side is a new but simple integration (local file reads against a
  repo checkout), not a new subsystem.

## Compliance impact

- PCI DSS: strengthens change-management evidence (requirement 6.x) that the release/versioning
  pipeline itself — not just individual code changes — stays in a verifiably consistent state.
- DORA: supports Art. 9 (ICT risk detection) and Art. 24 (testing of ICT systems) — this agent is
  the fleet-wide re-verification layer for the repo's own release-engineering invariants, the same
  role control-liveness-sentinel plays for operational liveness and governance-auditor plays for
  merged-PR compliance.
- GDPR: not applicable.
- PSD2: not applicable directly; a regressed or ambiguous release version on a payment-rail
  money-path service is exactly the kind of silent operational-continuity risk this agent surfaces
  before it reaches a live deploy.
- CNB: supports vyhláška ČNB 501/2002 Sb. change-management expectations by making "is the release
  pipeline's own state consistent" an auditable, continuously re-verified fact instead of an
  assumption that holds only until the next release-please merge.

## References

- [ADR-0031](0031-ai-agent-governance.md) — AI agent governance framework (charter shape, HITL,
  kill switch)
- [ADR-0029](0029-versioning-release-and-governance-as-code.md) — release-please / governance as
  code; rule #3, the release-registration invariant this agent re-verifies
- [ADR-0048](0048-decouple-api-contract-version-from-service-release-version.md) — the two
  independent version axes; the api-contract gate this agent's openapi-collision check
  complements
- [ADR-0112](0112-ai-finops-agent.md) — sibling control-plane agent (cost axis), the template this
  agent's shape follows
- [ADR-0119](0119-ai-devops-agent.md) — sibling control-plane agent (delivery axis)
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — sibling control-plane agent
  (operational liveness axis)
- [ADR-0164](0164-governance-auditor-ai-agent.md) — sibling control-plane agent (merged-PR
  compliance axis); same "a passing check is not proof the invariant holds" premise applied to the
  release axis here
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- `openbank-infra/scripts/check-release-registration.py` — the manifest/config lockstep gate this
  agent mirrors (incident 1)
- `.github/scripts/check-admin-ui-version-sync.sh` — the admin-ui version-sync gate this agent
  mirrors (incident 2)
- `.github/scripts/check-app-version-override.sh` — the `quarkus.application.version` override
  gate this agent mirrors fleet-wide (incident 3)
- `.github/scripts/check-api-contract.py` — the diff-base-blind api-contract gate this agent's
  open-PR collision check complements (incident 4)
- transaction-service manifest-drift regression (`1.10.0` → `1.0.0`) — motivating incident 1
- ledger spec `openapi.yaml:info.version` race, PR #481 vs. PR #524, corrected by PR #534 —
  motivating incident 4
