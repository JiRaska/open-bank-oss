---
date: 2026-06-01
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, docs]
summary: "GitHub Issues become the actionable backlog, with fleet-sweep and governance-task templates and labels managed as code; decisions stay in ADRs and changelog entries stay with release-please."
---

# GitHub Issues as the actionable backlog

## Context

The repository has never used GitHub Issues — `gh issue list` is empty and there are no
milestones — yet three kinds of work routinely have no sortable, closeable home:

1. **Fleet sweeps.** A decision often lands as the *same* change across the ~30 services,
   one PR per service (the proven authz `@Authorize` rollout was 14 PRs; the docs-as-service
   migration is ongoing). Progress today lives only in a contributor's head or in chat — there
   is no single place that shows "11 of 14 done".
2. **The actionable tail of an ADR.** An ADR records the decision, but its *consequences*
   frequently include pending work — "rollout pending" (ADR-0019), "5 regulatory conditions
   before go-live" (ADR-0027), a gate to flip from `advisory` to `enforced` (several gates in
   `rules.yaml`). That work is recorded nowhere trackable; it is rediscovered by re-reading
   the ADR.
3. **Bugs and feature proposals.** Issue *templates* for these already exist
   (`.github/ISSUE_TEMPLATE/{bug_report,feature_request}.yml`) and the PR template already has
   a "Linked issues / `Closes #NNN`" section — but the flow was never wired, so the templates
   and the link field sit unused.

Meanwhile the project's governing principle (ADR-0029) is *derive from code → enforce in CI →
surface in UI; nothing computable is typed twice*. Any tracking layer we add must obey that:
labels and templates as code, not click-ops, and no third source of truth that competes with
ADRs (decisions) or release-please (changelog).

## Decision

We will adopt GitHub Issues as the **actionable backlog layer** of the contribution flow,
governed as code.

- Issues track *what needs doing*. They do **not** hold decisions (those stay in `docs/adr`),
  changelog entries (release-please derives those from Conventional Commits), questions
  (Discussions), or security vulnerabilities (private Security Advisories).
- Two new issue-form templates capture the patterns that had no home: `fleet_sweep.yml`
  (a cross-service tracker with a per-service task list and a required driving ADR) and
  `governance_task.yml` (the actionable tail of an ADR, with a required source-ADR ref).
- **Labels are code.** `.github/labels.yml` is the single source of truth, applied by the
  `Label sync` workflow. The sync is **additive and idempotent** — create-or-update only,
  never delete — because removing a label mutates issue history; pruning stays a manual
  decision. Labels are never created in the UI (CLAUDE.md rule #7).
- **PRs link their issue.** The existing PR-template field is now load-bearing: `Closes #<n>`
  auto-closes on merge; a single sweep service PR uses `Refs #<n>` to leave the tracker open.
- **Risk tier carries through.** An issue touching a money-path service gets the `money-path`
  label; each resulting PR still requires 2 approvals + an up-to-date threat model
  (`rules.yaml: money_path_services`). Compliance labels mirror the PR template's compliance
  checklist so the two surfaces cannot drift.
- The rules are recorded once in `rules.yaml: issues` (gate `issue-hygiene`, `advisory`); the
  link-in-PR check flips to `block` only once the producing CI lint exists (ADR-0029 D7).

## Alternatives considered

- **External tracker (Linear / Jira).** Strong workflow tooling, but adds a system outside the
  repo, breaks the `Closes #<n>` commit linkage, and splits provenance from the signed
  evidence bundle (ADR-0029). Rejected: keep the backlog where the code and the audit trail
  already are.
- **Keep using only ADRs + milestones + memory.** The status quo. Rejected: it is exactly what
  leaves sweeps and ADR follow-ups untracked; ADRs are too heavy to open per unit of work and
  are not meant to be closed.
- **Third-party labeler action (e.g. crazy-max/ghaction-github-labeler).** Idiomatic, but adds
  a pinned-SHA dependency to babysit and, on a reused self-hosted runner, repeats the
  download-state fragility that already pushed secret-scan to a host CLI binary. Rejected in
  favor of a script. (The script first used `gh label`, on the gitleaks "host binary"
  precedent, but `gh` proved not uniformly available across the heterogeneous self-hosted pool
  — see Consequences — so it now calls the GitHub REST API directly with `curl`, depending on
  only the one binary present on every host.)
- **Destructive (pruning) label sync.** Simpler "the file is the exact label set" semantics,
  but a delete silently strips the label from every historical issue and would also nuke
  dependabot's own labels. Rejected for additive-only.

## Consequences

**Positive**
- Fleet sweeps and ADR follow-ups become visible, sortable, closeable work with a back-link to
  their decision.
- `Closes #<n>` gives a free, auditable issue↔PR↔commit trail — valuable for banking-grade
  provenance.
- Labels and templates are versioned and reviewable; no click-ops drift.

**Negative**
- A new convention to learn and to keep honest (an unlinked PR, a sweep PR that forgets
  `Refs #<n>`). Mitigated by the PR template and the `issue-hygiene` gate.
- The "host binary" assumption (carried over from gitleaks) proved fragile for this job, in three
  ways the first runs surfaced one at a time: `yq` is not installed on the macOS sandbox hosts at
  all; `gh` is absent on the Linux sandbox hosts and, on the macOS hosts, lives on a Homebrew PATH
  the runner *service* does not expose; and a manifest color (`5319e7`) was silently coerced to a
  number by YAML because it was unquoted. The workflow now depends on only `curl` (present on every
  host), talks to the GitHub REST API directly, fetches a version-pinned `yq` into `RUNNER_TEMP`,
  and the manifest quotes every color. Lesson: on a heterogeneous self-hosted pool, "assume the
  host has tool X" holds only for a tool every host demonstrably has.
- The manifest must also satisfy GitHub's own label constraints — notably the **100-character
  description limit** (one description overran it and the API rejected it mid-sync with a 422).
  Values are kept within the limits by review for now; a manifest lint belongs in the pending
  `issue-hygiene` producer (ADR-0029 D7) rather than as another host-tool assumption in the job.

**Neutral**
- `blank_issues_enabled: false` stays — every issue starts from a template.
- Runner labels (`self-hosted` / `openbank-build` / `openbank-deploy`) are a different
  namespace and remain out of `labels.yml`.

## Compliance impact

- PCI DSS:  not applicable (process change; `compliance:pci` / `pci-review-required` labels make affected work visible)
- DORA:     supports operational-resilience traceability (auditable change backlog); no control change
- GDPR:     not applicable; `compliance:gdpr` / `gdpr-review-required` labels surface PII-path work
- PSD2:     not applicable
- CNB:      not applicable; `compliance:cnb` label surfaces reporting-related work

## References

- ADR-0029 — versioning, release and governance as code (governing principle)
- ADR-0030 — supply-chain security (host-CLI over third-party action)
- ADR-0040 / ADR-0082 — CI execution model and runner governance (sandbox pool, runner labels)
- `openbank-libs/governance/rules.yaml` — `issues:` block (authoritative)
- `.github/labels.yml`, `.github/workflows/labels.yml`, `.github/ISSUE_TEMPLATE/`
