<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. -->
# Solo-maintainer review migration

Status: model accepted; hosted pilot prepared, **not activated**. The
existing agent guard and required checks remain authoritative during preparation.
Tracking: #2183. The proof reader in `.github/scripts/solo-review-proof.py` is an
offline-tested component, not evidence that hosted reviews or acceptance have run.

## Accepted review model

An author prepares the change; two separate AI sessions review the same immutable
PR head using different observed models. Each receives the complete changed-file
manifest and must explain its checks for every file. A failed invocation, omitted
file, truncated input, tool invocation or unresolved finding blocks admission.
Reviewer sessions cannot share conversation history or write repository state.

Changes classified as protected by the existing agent guard additionally require
the repository owner's acceptance after both reports are available. This includes
money-path services, authorization, CI/workflows, governance and new released
components. Ordinary authored changes require both reviews; deterministic release
automation must be classified explicitly before activation.

This replaces the development-review requirement for additional human maintainers.
It does not replace runtime four-eyes controls, threat models, tests, coverage,
security checks or required status checks. Review evidence alone does not authorize
a merge. A new PR head invalidates its evidence; a changed merge-base invalidates
the reviewed diff. An unrelated base-branch advance may retain review if the
merge-base and complete file manifest remain identical; CI still validates integration.

## Trust and bootstrap

The owner must first review and explicitly anchor the concrete policy commit in
the GitHub repository variable `SOLO_REVIEW_POLICY_SHA`. No PR field, comment,
label or candidate configuration can establish this trust. The anchored controller
reads target source as data and never executes its scripts with review credentials.
The workflow identity, source SHA, repository, event, run ID and attempt all form
the evidence provenance.

The hosted entry point is a distinct admission mode on the existing
`agent-review.yml` workflow, allowing a pinned candidate revision to be exercised
before default-branch installation. Its existing advisory mode is insufficient:
it is title-scoped, permits truncated input and only proves that one review happened.

The pilot never publishes a merge-admission success or changes the existing guard.
It produces evidence for an external reader. Before cutover, a trusted required
admission check must be proven and required; only then may the guard's refusal gain
an evidence-based exception. Candidate-controlled CI must not supply its own trust
anchor to waive the old refusal.

The producer receives the anchor through the repository `vars` context in jobs
without an environment. The external reader independently fetches the variable
through REST, which requires Variables-read permission unavailable in the ordinary
`GITHUB_TOKEN` permission vocabulary. Do not fix that by exposing an owner PAT to
PR code. Validate the consumer's read-only credential before integrating it into CI.

For protected changes, a `solo-review-owner` GitHub environment must require the
repository owner's User identity, disallow administrative bypass and pause only
after both full reports are available. The acceptance screen must identify the PR,
head, policy SHA and report artifacts. Final evidence is produced after acceptance
and a fresh head check. Fresh dispatches are required: workflow reruns are rejected
because review-history metadata does not bind an earlier approval to a new attempt.

Unattended agents must use an App credential without administrative or owner
acceptance capability. When a workstation agent shares the owner's credential,
GitHub cannot distinguish the person from the agent. Prohibiting automated acceptance
is then a process control, not cryptographic proof of an independent human action.
No automation may submit the owner's environment approval on their behalf.

## Activation and rollback sequence

1. Implement and negatively test the producer, immutable report format and reader.
   Keep the old refusal when the external anchor or valid evidence is missing.
2. Independently review the concrete controller commit. Obtain owner acceptance
   of that exact revision before setting the external anchor or running its code
   with model credentials. Confirm any provider and payload authorization.
3. Configure and verify the protected environment. Exercise an ordinary PR, a
   protected PR, an actual finding, a failed model call and a push during review.
   Confirm that waiting or skipped approval cannot produce successful admission.
4. Add the separate required admission context only after its producer is proven
   for every applicable PR class. Preserve every existing required context.
5. Only then let the revised guard accept this hosted proof. Rerun the relevant CI
   checks on the same candidate head. No administrative merge override or branch
   rename is part of bootstrap. Update `rules.yaml`, contributor guidance,
   automation prompts and policy parity checks together. Confirm ordinary changes
   progress and protected changes still wait for owner acceptance.
6. Revalidate outstanding PRs against their actual heads. Close issues only after
   their acceptance conditions are met; queue size alone is not completion evidence.

Rollback first stops new admission and invalidates pending admission statuses, then
restores the previous reviewed policy through a PR. Do not delete a required context
before its replacement is proven. Changing the external policy anchor requires
revalidating outstanding evidence; it is not a way to approve a candidate implicitly.

## Current verification

The proof-reader tests exercise separate model/session identities, missing coverage,
findings, driver failures, tool use, wrong repository/workflow/source, reused attempts,
changed PR heads and diff bases, incomplete API enumeration, environment drift,
skipped jobs and state changes during the complete API read sequence. They perform
no network requests or model calls. Hosted producer and rollout validation remain
required before activation.
