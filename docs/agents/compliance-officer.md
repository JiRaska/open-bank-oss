---
id: compliance-officer
plane: control
adr: ADR-0031
---

# compliance-officer

## Mission

Continuous AML, sanctions-screening, GDPR and PSD2 oversight of the running bank. Runs a scheduled
sweep (every 30 minutes, `AGENT_OVERSIGHT_ENABLED`) over transaction, sanctions-screening, consent,
audit, AML, dispute and clearing data, and raises a proposal whenever something looks wrong — a
missed screening, a consent gap, a pattern worth a human's attention. It never decides anything on
its own; it writes up what it found and puts it in the queue.

## Why this agent exists

A bank generates far more compliance signal than a small team can manually sweep for continuously.
The alternative to an always-on oversight agent isn't "a human does it instead" — in practice it's
"nobody does it until an audit or an incident forces the question". This agent exists to make
continuous oversight actually continuous, without ever letting a model make a compliance call on
its own authority.

## Human oversight

Every single output is a proposal into the admin-ui approval queue (`requires_human: every: proposal`
in `agents.yaml`) — there is no path from this agent's reasoning to a system side-effect. Segregation
of duties is enforced structurally: the approver can never be the same identity as the author, and
every approve/reject decision is recorded with a reason. See `/approvals` in the admin-ui and the
proposal lifecycle in `openbank-agent-service`'s `ProposalService`.

## Scope note

This charter was narrowed once already (see the `ui-assistant` charter for the incident that drove
it): compliance-officer, not ui-assistant, is the one charter allowed to read AML/sanctions/payments
data, and only because its entire output is gated proposals reviewed by a human with the relevant
role. If you're tempted to widen another agent's read scope to cover a compliance question, the
answer is usually "route it through this agent's proposal queue instead", not "widen the scope".

## Known gaps

- Feature-flag write proposals (`flags.write`, ADR-0067 / issue #419) are new — watch the approval
  queue for early false positives while operators calibrate trust in this proposal type.
- The by-actor audit query (used to show "everything this agent has ever proposed" in one place) is
  still planned — see ADR-0031 D5 in the roadmap for status.
