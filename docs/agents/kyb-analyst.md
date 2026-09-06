---
id: kyb-analyst
plane: business
adr: ADR-0284
---

# kyb-analyst

## Mission

Drafts a disposition proposal for a business-onboarding case that reached `MANUAL_REVIEW`. It reads
what the bank already has — the register extract verbatim, the applicant's declared data, the
beneficial-ownership finding and any sanctions hits — and proposes one of "resolve with N signers",
"reject, the entity is dissolved" or "request a power of attorney", with the evidence each rests on
cited by id. It joins the review as an ADR-0244 case participant. The operator disposes; nothing
about a case changes on the model's word.

Its second job is the more valuable one. When the Czech representation-rule parser marks a
*způsob jednání* phrasing `UNKNOWN`, that case lands in review for a reason no rule covers yet. The
agent proposes the signature count for that phrasing, and an **accepted** proposal becomes a new
pinned parser test. The judgement is captured as data and re-checked by CI forever, instead of being
trusted at runtime on the next case that reads like it.

## Why this agent exists

`MANUAL_REVIEW` is where ADR-0284 deliberately sends everything it cannot decide: an unparsed
representation rule, an initiator who is not listed in the register, a register that was down, a
beneficial-ownership finding that is `SELF_DECLARATION` or `UNAVAILABLE`. That queue is correct and
it is also where onboarding stops being fast. Preparing the file is exactly the work an agent can do
without deciding anything — the same division control-liveness-sentinel makes between "surface the
finding" and "fix the control".

The parser-test loop is the part worth defending. A model that reads free-text company law at
runtime is a model whose answer nobody can reproduce; the same model proposing a test case is a
model whose answer becomes a fixture with an expected value. The first is judgement in production,
the second is judgement in review — and only the second survives the model being replaced.

## Human oversight

- `every: proposal` — the agent has **no write tier at all**. It cannot resolve a review, reject a
  case, or touch a mandate; the deny block spells that out rather than relying on the absence of a
  grant.
- `approver_must_differ_from: author` — segregation of duties, as for every proposal-only charter.
- `record: reason` — the operator's disposition carries a reason, so a rejected case can be audited
  against what the agent proposed.
- `case.join` only. Opening, coordinating and synthesizing a case stay with case-coordinator.

## Known gaps

- **No runtime exists.** The charter is deliberately ahead of the loop: `enabled: false`, and
  nothing in this repo executes it today. An agent's powers are cheap to bound before anyone writes
  the code that uses them and expensive afterwards, which is the whole reason this file lands with
  the design rather than after it.
- **PII is masked, and that limits the initiator-match proposal.** A representative's name arrives
  masked, so the agent can propose "the initiator is not among the listed representatives" from
  structure, not from reading two names side by side. Where the decision genuinely needs the
  cleartext comparison, it stays with the operator — which is the correct answer, not a workaround.
- **The beneficial-ownership input is only as good as the jurisdiction.** For GB the PSC register
  answers; everywhere else the finding is `SELF_DECLARATION` or `UNAVAILABLE` (ADR-0284 D5), and a
  proposal that treats those as equivalent to a register answer would be confidently wrong. The
  distinction is carried in the data for exactly that reason.
- **It sees cases in `MANUAL_REVIEW` and no others.** A live customer's case is out of scope by
  data scope, not by prompt.
