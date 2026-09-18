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
file, truncated input, execution-tool invocation or unresolved finding blocks admission.
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
before default-branch installation. The legacy advisory mode has been removed: it permitted truncated input and
used a personal subscription credential. Only explicit admission dispatch remains.

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

## Structured result transport

Claude CLI can deliver `--json-schema` output through its internal `StructuredOutput`
call ([upstream transport example](https://github.com/anthropics/claude-agent-sdk-python/issues/1013)).
The reader distinguishes that output carrier from execution tools. Every exact
`StructuredOutput` attempt is retained in the report, and the last attempt must match
the final `structured_output` object. A native structured result may have no carrier.
The verifier rejects any earlier finding or unresolved verdict, even if the final
answer is clean. The CLI's exact single-key `$PARAMETER_VALUE` envelope is decoded
once with duplicate-key rejection; malformed, nested or ambiguous envelopes block.
Raw attempts remain in the evidence, and every decoded attempt is checked before the final
answer claims NO_FINDINGS; missing attempt history also blocks multiple outputs. Both execution calls and server-side tool calls still block;
an output carrier cannot excuse a sibling tool call. Evidence separately records
`structured_output_uses`, while `tool_uses` counts execution calls. Findings and coverage
validation are unchanged. No free-form JSON recovery or fallback verdict is introduced.

The hosted pilot at policy `a5ae994db25543099305322d6657945a2529979f` failed both
review slots with `review used tools`; its raw output was not retained, so this transport
fix still requires a fresh hosted run to establish that it resolves that particular failure.
The local provider smoke test could not authenticate; offline regressions are not hosted
proof. Re-anchor only after owner acceptance of the new policy revision; this change does
not update the external anchor, submit owner acceptance, or grant merge admission.

## Hosted pilot findings under remediation

The pilot at `99df055d80701135e5e3e93ddd32bf0d7e329fe5` did not produce
admission evidence. Correctness stopped on multiple StructuredOutput carriers;
security produced a report with findings. A successful report job is not a clean
review. That controller rejected multiple carriers. Count-only diagnostics now distinguish
repeated call identities and agreement with the final result without logging model
content, tool arguments or credentials. Their actual hosted shape remains unproven.

The CLI child now receives an explicit environment allow-list, retaining only basic
process settings and the required provider credential. Repository, Actions runtime
and future workflow secrets are excluded. A deleted fork is classified as unresolved
public provenance rather than raising an unhandled AttributeError.

The installed CLI version matching the pinned dependency, 2.1.233, documents
`--tools ""` as disabling all built-in tools. This is a different option from
`--allowed-tools`; the latter's historical behavior is not evidence about the former.
Help output establishes the documented contract, not runtime isolation proof.
The producer now checks the CLI initialization tool inventory, supplies unchanged
classifier and gate-runner source, and compares classifier/rules blob identities
against the live base-branch tip resolved through the branches API before
preparation, review and sealing. This deliberately does not use the PR record as
the authority for the current branch tip; the verifier rereads that tip before success. The external reader
independently performs the same policy comparison. Any policy drift requires reviewed
re-anchoring; unrelated base advances remain allowed when these files are unchanged.
These new checks still require a hosted pilot run.
No finding is waived by these changes, and no external anchor is updated implicitly.

## Limits of model independence

Different observed models and isolated sessions establish separate invocations, not
statistical independence or resistance to prompt injection. Both reviewers read the
same untrusted source; an embedded instruction can steer both to a false clean verdict.
The system instruction, tool isolation, complete coverage metadata and two model names
do not prove that this did not happen. A prompt digest or a second framing would not
by itself prove it either. Review evidence is supplementary to deterministic checks
and the owner's acceptance for protected changes; it must never be described as a
security certification. Ordinary changes retain this residual risk in the accepted
model. No merge-admission cutover is authorized by this pilot.

## Observed legacy output argument wrapper

The hosted CLI also emitted earlier StructuredOutput arguments as a single-key
object `{"$PARAMETER_VALUE": "<JSON review object>"}` before returning a direct
review object. The verifier decodes only this exact wrapper when inspecting history,
retains its original representation, and applies the same no-findings requirement.
Unknown shapes, malformed JSON and duplicate JSON keys are rejected. This is not
recovery from arbitrary model text, and never substitutes for the final structured
result. The last carrier must still match that result exactly. The regression was
reproduced and corrected against both unchanged hosted reports from the pilot;
a fresh complete hosted run is still required for admission.

## Usage accounting

Each solo model invocation writes a separate `review-usage-<slot>-1` artifact,
including on invocation failure. It contains allowlisted numeric CLI usage and cost,
the subject digest, and process status. Missing values are null (unknown), never
inferred as zero. CLI cost is not an invoice or evidence of subscription charges.
A timeout can leave incomplete accounting; reconcile against provider billing.
The record is written before invocation so an interrupted process may leave a
`started` record. Runner loss can still prevent artifact upload.

Accounting is not an admission report and cannot make a failed review pass. It
does not enforce an account-wide spend cap. The runner rejects serialized input
above 1 MB without truncation, sets a 16,384 output-token limit and passes
`--max-budget-usd 1.00` to the CLI for each slot. The CLI budget is a secondary
stop condition, not a prepaid reservation or an invoice-level guarantee; an
in-flight request may already have incurred cost. Shared budget reservation and
provider reconciliation are required before unattended retries can be enabled.
Limits also mean a large PR must be split; exceeding one never counts as approval.


## Guard integration candidate (2026-09-17)

The owner has selected development review by AI sessions plus the sole owner's
acceptance instead of requiring additional human maintainers. Runtime maker/checker
controls are unchanged. This implementation is a candidate, not an activated policy.

The guard discovers `solo-review/evidence` on the current PR head. That status is
only a locator: its author and its successful state cannot authorize a merge. The
guard independently verifies the referenced run, complete reports, immutable head
and diff base, controller identity and protected environment acceptance. Missing,
stale, failed or unreadable evidence retains the refusal. The existing required
agent guard remains the admission check; the locator must not replace that check.

The hosted guard receives the repository policy variable through the reviewed CI
workflow's `vars` context, avoiding an owner PAT in a candidate job. This is a
job-start configuration snapshot, not a fresh REST read. The external verifier
continues rereading the variable through REST. Changing the anchor requires
invalidating/rerunning pending CI; a completed check cannot observe future changes.

For the initial policy transition only, review of the exact externally anchored
controller commit may observe its single parent's policy on the base branch.
The entire classifier/rules snapshot must match that parent. This does not apply
to application PRs, mixed snapshots or later policy drift. The candidate still
requires both real model reports and explicit owner environment acceptance; it
cannot grant those to itself. After merge, the base policy must match the anchor.

Activation remains pending. The unmerged #10172 is closed; #10171 tracks the
remaining provider spending controls. Each invocation now requires repository
variable `AGENT_REVIEW_API_ENABLED=true` and secret
`AGENT_REVIEW_ANTHROPIC_API_KEY` for a dedicated API project. Set a provider-enforced
spending limit on that project before enabling it. The runner fails before any
provider call when either prerequisite is absent. Its temporary HOME and config
paths prevent discovery of a personal CLI login; OAuth credentials are excluded.
The complete workflow serializes dispatches through `agent-provider-budget` and
never cancels an active paid run for a newer dispatch. Before preparing a subject,
the shared dispatch admission checks that both the workflow revision and checkout
match the externally pinned owner anchor. The explicit `--owner-anchored` mode
allows the initial policy transition from a branch; it does not exempt that run
from the shared history, failure circuit or allowance. The default advisory mode
continues to require the current default-branch controller. Both modes reject
reruns and incomplete history and permit at most two dispatches in seven days.
A prior failed, cancelled or unresolved dispatch opens the circuit.
The allowance covers the whole two-model dispatch; it is not a provider billing cap.
The subject preparation and independent verifier still enforce the single-parent
policy transition described above. Automatic retries remain disabled;
unknown usage must be reconciled before another paid dispatch. Do not restore the
removed personal OAuth credential or enable the retired invocation path. The new
locator job makes no model calls and does not submit owner acceptance. No source
change here configures an external anchor, changes required contexts or deploys a
banking service.


### Required trust boundary

`solo-review-admission.yml` runs from `main` through `pull_request_target` or a
main-branch manual dispatch. It checks out only the external policy anchor, never
the PR head, and publishes `solo-review/admission` on the freshly verified subject
head. The ordinary PR CI bridge is not an independent authority: candidate code can
modify its own verifier. Before activating that bridge, require the separately
trusted admission producer and bind its identity through repository protection.
A bare status name produced by any workflow sharing the same App is not a source
identity guarantee. If the repository cannot bind the trusted producer, do not
claim the candidate CI bridge creates that boundary.

The initial installation still requires explicit owner-controlled activation of
this reviewed policy. A workflow absent from the default branch cannot protect
its own installation. The parent-policy transition permits review evidence, not
self-approval or an administrative merge bypass. Re-dispatch the trusted admission
workflow after the review workflow completes, then rerun the ordinary failed CI.
