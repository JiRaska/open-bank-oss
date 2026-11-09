---
id: regulatory-obligations-watcher
plane: control
adr: ADR-0367
---

# regulatory-obligations-watcher

## Mission

Once a week, read the legal text and the authority specification that each row of the regulatory
obligations register cites, compare them with what the row says (deadline, cadence, format,
transport), and open a proposal wherever they disagree or a row's deadline is still marked
`unverified`. It proposes and nothing else. It never edits the register, a report or a filing.

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
- `any_register_change`: the agent never writes to the register. `tools.deny` blocks the write
  and execute tiers explicitly.
- `runs_per_day: 1` and `tokens_per_run: 60000`: a weekly read of a few dozen sources needs no
  more than that.

## Why it ships disabled

`enabled: false`. No fetcher for the cited sources is wired yet. An agent that cannot read its
input reports nothing, and that silence would read as "the register is current". The flag flips in
the PR that wires the fetcher, so "it runs" and "it can see anything" become true together.

## Known gaps

- No source fetcher, as above.
- Many sources are consolidated legal texts in HTML or PDF with no change feed. Detecting a change
  means comparing against the last read, not subscribing to a notification.
- It proposes corrections and does not track whether anyone acted on them. An open, ignored
  proposal is covered by the governance follow-up rule (ADR-0052), not by this agent.
