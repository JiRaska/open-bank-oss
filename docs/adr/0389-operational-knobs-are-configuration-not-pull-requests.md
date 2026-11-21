---
date: 2026-11-21
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance, resilience]
followup: "none — delivery tail (knob registry fields, audit snapshot, hardcoded-constant guard) is tracked by the governance issue linked from the PR"
summary: "Delivery-automation knobs are classified SAFE, GUARDED or NEVER: SAFE ones are validated repo variables changeable in minutes, GUARDED ones owner-only and audited, NEVER ones stay behind a reviewed PR; stopping never needs a PR."
---

# ADR-0389 — Operational knobs are configuration, not pull requests

## Context

ADR-0047 decided what a governed *runtime* control plane may change inside running services
(break-glass resilience controls and business-policy parameters) and left everything else in
GitOps. ADR-0067 decided how *service* feature flags are defined and evaluated. ADR-0343
introduced the merge train. None of them decides how the behaviour of the delivery automation
itself — workflows, the merge train, deploy scripts — is tuned. This ADR is needed because
that gap is where the cost now sits: pacing, batch sizes, timeouts, runner choice and similar
values are mostly literals in `.github/workflows` and scripts, so changing them takes a PR,
which itself has to pass through the very train or queue whose behaviour is being tuned.

Evidence measured on `origin/main` (2026-11-21,
`git grep -ohE 'vars\.[A-Z0-9_]+' origin/main -- .github | sort -u | wc -l`): 47 distinct
`vars.X` names are read under `.github`, but only 7 of them are ever read in the
`vars.X || <default>` shape (9 reads in workflows), and of those only `MERGE_TRAIN_RUNNER`,
`DEPLOY_DROPPED_PIN_HOURS` and `MINI_RUNNER_SERVICES` are operational tuning — the rest are
endpoints, usernames and app ids; every variable is already registered in
`.github/config-inventory.yaml` and checked by the enforced `config-inventory` gate. So the
mechanism exists and works; what is missing is the rule saying when to use it and when not.

## Decision

We will classify every operational value in delivery automation into one of three classes.

1. **SAFE knob** — pacing, throughput, concurrency, capacity, timeouts, retry counts, batch
   sizes, runner selection, and switching *off* a non-security feature. It MUST be read as a
   repository or environment variable with an inline default, `vars.X || <default>`, and the
   consuming code MUST range-validate it: an invalid value falls back to the default and
   emits a warning (`::warning::`), never a failure. Maintainers may change it at any time.
2. **GUARDED knob** — kill switches in the dangerous direction (re-enabling something that
   was stopped), widening an owner bypass or allow-list, WIP limits. A variable is allowed,
   but only the owner changes it, and every change is recorded (who, when, old value, new
   value) in an audit snapshot. The safe direction of a guarded knob — stop, freeze, add a
   hold — is always available instantly to maintainers.
3. **NEVER knob** — required checks and rulesets, policy blobs, money-path rules, approval
   requirements, removal of deploy holds, and security controls. These stay behind a
   reviewed PR, because the reviewed change history is the regulatory evidence for them.

Rules that follow:

- **Tie-break.** A value that fits more than one class takes the stricter one. A limit that
  bounds how much unreviewed work may be in flight (a WIP limit) is GUARDED even though it is
  also a concurrency value; a timeout or concurrency setting on a security or money-path check
  is NEVER.

- **Fast-direction principle.** Stopping, freezing or rolling back automation must never
  require a PR. Any control whose safe direction needs a PR is a defect against this ADR.
- A new hardcoded operational constant in workflows, the merge train or deploy scripts needs
  a stated justification in the PR (why it is not a SAFE knob).
- When an existing literal is converted, its default equals the current value, so the
  conversion changes no behaviour.
- Every variable is registered in `.github/config-inventory.yaml` (already enforced), and
  each knob is documented with its default, valid range and class.

## Alternatives considered

- **Keep every value in code, change by PR.** Full history in git and review on every change.
  Rejected: tuning the train or a deploy pace then waits on the train itself, and the safe
  direction (stopping) is as slow as the dangerous one.
- **Make everything a variable.** Fastest possible tuning. Rejected: rulesets, required
  checks, approvals and money-path rules would then change without review and without the
  git history auditors rely on.
- **Extend the ADR-0047 runtime control plane to cover CI.** One governed surface for all
  knobs. Rejected: that plane acts on running services with four-eyes desired-state records;
  delivery automation already has a native, inventoried mechanism (repository variables), and
  building a second plane for it adds infrastructure without adding control.

## Consequences

**Positive**
- Pacing and capacity problems are fixed in minutes, without a PR through the queue they
  slow down.
- Stopping and freezing are always instant, which is the direction incidents need.
- The class makes the review question explicit: a NEVER value proposed as a variable is
  rejectable on sight.

**Negative**
- Configuration drift becomes invisible in git: a variable's current value is not in the
  tree. Mitigation: the `config-inventory` gate (names, enforced) plus an audit snapshot of
  values for GUARDED knobs, compared on a schedule.
- Range validation is code each consumer must carry; an unvalidated variable is a new way to
  break a workflow.

**Neutral**
- Existing literals are not converted in bulk; they move when someone next needs to tune them.

### Enforcement

- `config-inventory` (enforced) already covers registration.
- Follow-up: a CI guard that flags a new hardcoded numeric `env:` value in
  `.github/workflows`, introduced in advisory mode first and promoted once its false-positive
  rate on `main` is measured.

### Delivery check

- `python3 .github/scripts/check-config-inventory.py` exits 0 (every variable registered).
- `grep -rhoE "vars\.[A-Z0-9_]+ \|\| " .github/workflows | wc -l` grows over time from the
  2026-11-21 baseline of 9, and the follow-up guard exists in `.github/gates/gates.yaml`.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface; payment-path rules are NEVER knobs.
- DORA: change-management of ICT operations — operational tuning gains an audited path while
  control-relevant changes remain reviewed changes.
- GDPR: not applicable — no personal data is processed by these knobs.
- PSD2: not applicable — no payment-service behaviour changes through this mechanism.
- CNB: not applicable — supervisory controls stay behind reviewed PRs (NEVER class).

## References

- ADR-0047 — governed runtime operational control plane
- ADR-0067 — feature flags and experimentation
- ADR-0343 — merge train replaces strict up-to-date on main
- `.github/config-inventory.yaml`, `.github/scripts/check-config-inventory.py`
