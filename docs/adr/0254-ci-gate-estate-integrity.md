---
date: 2026-08-09
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance]
summary: "Three checks over the gate estate: required-context/workflow parity, a dated expiry on advisory 'benign' verdicts, and whether an incident write-up produced a gate."
followup: "#4339 — advisory-finding-staleness needs every existing advisory gate re-annotated with a verified: date before it can enforce; incident-gate-coverage stays advisory permanently by design (see Decision)."
---

# ADR-0254 — CI gate estate integrity: ruleset parity, advisory-finding staleness, and incident coverage

## Context

#4339 audited all 129 gates in `.github/gates/gates.yaml`, found gates that passed
without examining their subject, gates that ran their own self-test twice, and four
workflows duplicating a declared gate. Four PRs (#4340, #4343, #4344, #4346) closed
those, taking the `gates (gitops)` shard — the critical path of the required
`Validate manifests` check — from 62s to 23s in CI while adding three more gates.

Closing #4339 raised the next question directly: is that now the best CI in the
industry, or are there classes of defect the audit's method — read the manifest,
profile it, falsify each fix against a known-positive — cannot reach at all? Three
survive that test:

1. **The required-status-check list and the workflow that is supposed to satisfy it
   can silently disagree.** Landing #4340-#4346 required deleting four workflows
   (`adr-registry.yml`, `eu-ai-act-registry.yml`, `agent-charter-registry.yml`,
   `ai-governance-snapshot.yml`) that duplicated declared gates. Before deleting them
   I had to read `main-protection`'s required contexts by hand
   (`gh api repos/.../rulesets/<id>`) to confirm none of their job names was one.
   That check exists nowhere as code. The failure mode it guards against is not
   hypothetical — CLAUDE.md's CI section documents the adjacent case twice already:
   "A PR that is conflicted AT CREATION never gets a merge ref" (a required context
   that can structurally never report) and "`main`'s own CI conclusion is the one
   signal with no reader." A required context with no workflow to satisfy it strands
   every PR forever; a new required job nobody declared in a workflow silently
   narrows enforcement the same way #3629's conditional-job defect did.

2. **An advisory gate's "these findings are benign" note has no expiry.**
   `incluster-hostname-resolution` shipped advisory with a triage note claiming all
   six findings were dead `rest-client` defaults overridden by env. Three were live —
   settlement-service and onboarding-service dialled hostnames that resolved in no
   namespace, on wrong ports (CLAUDE.md, "An advisory gate's 'these findings are all
   benign' note is an unverified claim..."). The note was never re-checked against
   the deployed state after it was written. Nothing in `gates.yaml` distinguishes a
   triage that was verified yesterday from one that was verified the day the gate
   was written and never looked at again.

3. **Nothing measures whether a documented incident produced a gate.** The tracked
   `CLAUDE.md` holds dozens of paragraph-length write-ups, each naming an issue
   number and (usually) the check that now catches the class of defect it
   describes. Whether that check actually exists — versus the write-up being the
   only artifact — is established today by a human re-reading the paragraph and
   grepping for the issue number. That is exactly the kind of claim #4339 mechanised
   for gate-script-registration and gate-invocation-reachability; there is no reason
   the same discipline should stop at the boundary of prose.

   `.claude/CLAUDE.md` and `.claude/rules/*.md` hold the same shape of write-up and
   are explicitly out of scope: they are gitignored (`.claude/` in `.gitignore`),
   deliberately private, and structurally absent from a CI checkout — a gate cannot
   read what was never cloned. This is worth stating rather than silently scoping
   around, because it is the reason a genuinely private incident (a break-glass
   procedure, an internal war-story with account specifics) can never get this
   check's coverage, by the same design that keeps it off the public repo at all.

## Decision

**We will add three checks to the gate estate, at three different enforcement
strengths appropriate to how mechanical each verdict is:**

- **`ruleset-context-parity`** (enforced). Reads the required-status-check contexts
  from `main-protection` via `GET /repos/{owner}/{repo}/rulesets/{id}` and the job
  names emitted by every `pull_request`-triggered workflow under `.github/workflows`,
  parsed the same way `check-gate-invocation-reachability.py` already parses
  `ci.yml` (job names, not step names — a required context is a *check run* name,
  which for a plain job equals the job's `name:`). Fails when a required context
  matches no job name anywhere in the tracked workflows (the workflow was deleted or
  renamed and the ruleset was not updated — a PR-stranding defect with no recovery
  short of a ruleset edit) and warns (does not fail — see Consequences) when a
  workflow declares a new always-run job that looks like it should be required but
  is not yet in the ruleset, since that is a judgement call about scope, not a
  structural break.

  This needs `permissions: administration: read` on the job that runs it — a new
  permission grant, which is itself a governance-machinery change and will draw the
  same human-review requirement every other `gates.yaml`/`rules.yaml` PR does.

- **`advisory-finding-staleness`** (enforced, ratcheted). Every gate with
  `mode: advisory` in `gates.yaml` must carry a `verified: "YYYY-MM-DD — <what was
  checked>"` field. The gate fails when the date is more than 180 days old, or
  absent on a NEW advisory gate (existing advisory gates without one are baselined,
  same shape as #4339's `check-gate-selftest-declaration.py` DEBT list, and must be
  annotated before the baseline is allowed to shrink to zero — see Followup). This
  does not re-verify the CLAIM (that is the gate's own job) — it only forces the
  claim to be re-dated by a human periodically, which is the minimum structural fix
  for "a note that was true once is trusted forever."

- **`incident-gate-coverage`** (advisory, permanently — see Alternatives). Extracts
  every `#<n>` issue reference from the tracked `CLAUDE.md`'s bullets that match
  the "operational footgun" or "hard rule" shape (a paragraph ending in a named
  fix), and reports which ones are never cited from `gates.yaml` or `rules.yaml`.
  Scoped to the tracked file only — `.claude/CLAUDE.md` and `.claude/rules/*.md`
  are gitignored and structurally absent from a CI checkout, so this can only ever
  measure the public half of the repo's incident record, which is stated as a
  limit, not fixed around. It does not fail a PR — it is a standing report,
  refreshed on a schedule like `verification-metadata-drift.yml`, that turns "did
  we gate what we learned" from a manual grep into a number that goes into the
  health snapshot this ADR's sibling (ADR-0255) makes visible.

## Alternatives considered

- **Retroactively mine every historical CI run to find gates that have never caught
  anything real.** Rejected. It requires parsing thousands of historical job logs,
  which is exactly the fragile territory CLAUDE.md's CI section warns about
  repeatedly (a job log contains the step's own `run:` script text, a log-search
  match on old prose reads as a false positive or false negative depending on
  direction). ADR-0255's gate-health snapshot answers the same question — "when did
  this gate last go red on a real PR" — going forward, from structured per-gate
  output rather than log-scraping, at a fraction of the engineering cost. Archaeology
  over history that predates structured output is not worth building a parser for.

- **Mutation testing (`mutmut`/`cosmic-ray`) over the 113 `check-*` scripts, to
  measure whether existing self-tests are strong enough.** Rejected for now. It is
  expensive to run at this scale (mutation count x self-test runtime, repeated per
  PR or nightly), and it answers a narrower question than the one currently open:
  #4339 measured that 44 of 133 gates have **no self-test at all** — the debt list
  `check-gate-selftest-declaration.py` tracks. Mutation testing tells you whether an
  *existing* self-test is weak; it says nothing about the 44 that do not exist.
  Fixing the larger, cheaper gap first is the better trade. Revisit once that debt
  list is materially smaller — the framing this ADR itself models for
  `advisory-finding-staleness`: state the follow-up condition, don't build for a
  problem that is not yet the binding constraint.

- **Make `incident-gate-coverage` enforced** (fail a PR that adds a CLAUDE.md bullet
  with no matching gate). Rejected. Not every incident write-up should have a gate —
  several documented in CLAUDE.md are one-off human-process failures (a merge
  guard's argument order, a monitor built with the wrong shell quoting) that a
  mechanical check cannot express, or genuinely cost more to encode than to remember
  by prose. Forcing a gate for every paragraph would produce either a wave of
  low-value mechanical checks or a wave of `# no-gate: reason` suppressions — the
  same hand-kept-exemption-list trap this repo's CI section names repeatedly.
  Advisory keeps the number honest without forcing either failure mode.

- **Hand-maintain the required-context list in a checked-in file and diff it against
  the ruleset on change, instead of calling the API live.** Rejected outright — this
  is the exact "a gate whose scope is a hand-kept list reads as passing when the
  list is short" shape CLAUDE.md names as this repo's most-repeated CI defect class.
  The ruleset is queried live, every run.

## Consequences

**Positive**
- A required context that stops being emitted by any workflow — the deleted-workflow
  case that motivated this ADR — is caught mechanically instead of by a human
  reading `gh api` output before every deletion.
- An advisory "benign" verdict now has a shelf life. The `incluster-hostname` defect
  class (three real findings sitting under a note that said "all benign") cannot
  recur silently past 180 days.
- "Did this incident get a gate" becomes a number in the health snapshot (ADR-0255)
  rather than a claim nobody can check without re-reading two files by hand.

**Negative**
- `ruleset-context-parity` needs a new job-level permission (`administration: read`).
  It is read-only and scoped to the job that calls the ruleset API, but any new
  permission grant is worth naming as a cost, not waving through.
- `advisory-finding-staleness` adds recurring authoring work: every advisory gate's
  claim needs re-dating roughly twice a year. That cost is the point — it is cheaper
  than a note staying "verified" for a year while three of its six findings quietly
  went live.
- `incident-gate-coverage` can only ever be advisory (see Alternatives), so it
  cannot block a PR that skips writing a gate for a genuine incident. It converts a
  silent gap into a visible number; it does not close the gap by itself.

**Neutral**
- None of the three touches a money-path service, a secret, or a runtime workload.
  All three are pure CI/governance-layer additions, same blast radius as #4339's
  four PRs.

## Compliance impact

- PCI DSS: not applicable — internal CI governance tooling, no cardholder data path.
- DORA: not applicable — this ADR is not itself a citation of a DORA ICT
  risk-management article; it improves engineering practice, which is a different
  claim from a regulatory one.
- GDPR: not applicable — no personal data involved.
- PSD2: not applicable.
- CNB: not applicable.

## References

- #4339 (the audit), #4340/#4343/#4344/#4346 (its four PRs)
- CLAUDE.md: "CI gates — exercise the failure path before trusting the green" (the
  `incluster-hostname-resolution` advisory-decay bullet, and the sibling bullet on a
  red advisory check being indistinguishable from a verified-benign one)
- CLAUDE.md: "A PR that is conflicted AT CREATION never gets a merge ref" and
  "`main`'s own CI conclusion is the one signal with no reader" — the two existing
  write-ups this ADR's `incident-gate-coverage` gate would itself flag as
  gate-worthy, since `ruleset-context-parity` only partially covers the same ground
  from the opposite direction (contexts vs. jobs, not runs vs. PRs).
- ADR-0255 — CI/QG health snapshot and observability (sibling ADR; consumes
  `incident-gate-coverage`'s output).
