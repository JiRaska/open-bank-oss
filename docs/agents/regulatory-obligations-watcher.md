---
id: regulatory-obligations-watcher
plane: control
adr: ADR-0367
---

# regulatory-obligations-watcher

## Mission

Once a week, read the legal text and the authority specification that each row of the regulatory
obligations register cites, compare them with what the row says (deadline, cadence, format,
transport), and file a governance issue wherever they disagree or a row's deadline is still marked
`unverified`. Filing that issue (`draft.ticket`) is its only write: it opens no PR and never edits
the register, a report or a filing.

## Why this agent exists

The register is the bank's answer to "which reports do we owe, to whom, and by when". Its value
depends on the rows staying true, and the sources change on their own schedule: a decree is
amended, the central bank republishes a reporting calendar, the deposit-insurance scheme changes
its file format. None of that reaches the repository on its own. An unnoticed change shows up as
a missed or rejected filing. Most deadlines in the register are still `unverified`, too, so most
of the useful work is confirmation: fetch the cited source, quote the decisive sentence, propose
flipping the row to `verified`.

## Human oversight

- `any_legal_interpretation`: whether a changed paragraph changes our duty is a legal
  judgement. Every finding is a proposal that a human confirms or rejects.
- `every: proposal`: every finding is an issue for a human to act on.
- `any_register_change`: the agent never writes to the register. Its charter lists the write and
  execute tiers under `tools.deny`, but `agents.rego` only evaluates that deny when the caller
  sends `input.tier`, which the MCP and REST paths do not. Today the real protection is
  deny-by-default: nothing outside the allow list is granted. The PR that enables the agent must
  include an `opa eval` against the served bundle with a must-DENY control (write, execute) and a
  must-ALLOW control (read, `draft.ticket`).
- `requires_human` is declarative: `agents.rego` notes that no code path reads it. It records
  intent; the controls above are what enforce it.
- `runs_per_day: 1` and `tokens_per_run: 60000`: a weekly read of a few dozen sources needs no
  more than that.

## Why it ships disabled

`enabled: false`. No fetcher for the cited sources is wired yet. An agent that cannot read its
input reports nothing, and that silence would read as "the register is current". The flag flips in
the PR that wires the fetcher, so "it runs" and "it can see anything" become true together.

## Preconditions for enabling

- **Dedupe.** Each finding carries the key `obligation id + source URL + SHA-256 of the fetched
  source content`. An issue whose key matches an open or previously closed issue is not filed
  again, so an unchanged `unverified` row produces one issue, not one per week. A changed source
  hash is a new finding.
- **Per-run cap.** At most 10 issues per run; anything beyond is summarised in the last issue
  instead of filed separately, so a fetcher fault cannot flood the tracker.

Both must exist and be tested in the PR that sets `enabled: true`.

## Known gaps

- No source fetcher, as above.
- Many sources are consolidated legal texts in HTML or PDF with no change feed. Detecting a change
  means comparing against the last read, not subscribing to a notification.
- It proposes corrections and does not track whether anyone acted on them. An open, ignored
  proposal is covered by the governance follow-up rule (ADR-0052), not by this agent.
