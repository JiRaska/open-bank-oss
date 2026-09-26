---
id: card-dispute-evidence-agent
plane: control
adr: ADR-0283
---

# card-dispute-evidence-agent

## Mission

Draft the evidence bundle for an open card chargeback — the authorisation, the clearing that
followed it, and the merchant as the network identifies it — and put it in front of a human before
the network's respond-by date. It drafts; a person files.

## Why this agent exists

A chargeback is won or lost on what was filed before the deadline, and the deadline belongs to the
network: it does not pause while somebody hunts for the paperwork. Assembling a bundle means
joining the authorisation row, its clearings, the merchant descriptor as the scheme resolves it and
whatever the customer sent in — four sources, none of which is where anyone is already looking.
The failure mode is not a wrong bundle; it is an empty one filed late, which loses the money
silently and looks like nothing happened.

## Human oversight

- `any_evidence_submission` — `POST /api/v1/card-disputes/{id}/evidence` reaches the scheme, and a
  submission cannot be withdrawn. The agent has `write_proposal` and nothing else; the whole write
  and execute tiers are denied explicitly rather than by omission.
- `any_dispute_status_change` — moving a case between bank states is an operator's judgement about
  money, not a drafting task.
- `runs_per_day: 4`, `tokens_per_run: 40000` — a desk with a daily rhythm does not need more, and
  the cap keeps the agent's own cost below the work it saves.

## What it may read, and what it can never see

`read.governance` only, and the card is referenced by its card-issuance id with the party by id.
No PAN, no CVV and no token credential exists inside card-processing to read — keeping them out of
that service is the design ADR-0283 states — so this agent sits outside the cardholder-data
environment by construction rather than by policy.

## Why it ships disabled

`enabled: false`, and this is the part to read before turning it on.

**The evidence it would assemble is not reachable yet.** The desk and the endpoint exist as of
ADR-0283 phase 3, and the sources do not:

- there is no document store this agent can read, so "the customer's evidence" has no location;
- the 3-D Secure result is not persisted anywhere it could reach, so the strongest single piece of
  evidence in a fraud chargeback is unavailable;
- `openbank-dispute-service`, which holds what the customer actually said, is not wired to the
  scheme case at all — a case there can be resolved as `CHARGEBACK` today while no chargeback is
  ever filed with a network (#8869).

Switched on against those gaps, the agent would assemble a bundle out of an authorisation row and
call it evidence, and nothing downstream could tell that apart from a real one. That is the exact
shape this repository keeps re-learning: an output that is well-formed, plausible, and about
nothing. It is worse here than a silence would be, because a reviewer sees a filled-in draft and a
deadline, and the cheapest action is to file it.

Flip `enabled` in the PR that gives it something to read, and author its prompt in the same change
so "it runs" and "there is a prompt" become true together
(`openbank-libs/governance/prompts/registry.yaml`).

## What would make it trustworthy

A bundle that names every record it drew on, with the case's `networkCaseId` and `respondByDate` in
the audit capture, so a reviewer checks the assembly rather than trusting it. Both fields are
already in the charter's `audit.capture`.
