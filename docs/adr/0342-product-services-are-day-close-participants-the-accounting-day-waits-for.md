---
date: 2026-10-17
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounting-close, ledger, interest]
summary: "Product services that post on a calendar report a per-accounting-day participation record to ledger-service; a day leaves CUTOFF only when every declared participant reported or an operator waived it, and a refused post is reported, not dropped."
followup: "#13167 — billing, lending, treasury, pension and pension-fund participants follow interest one PR each"
---

# ADR-0342 — Product services are day-close participants the accounting day waits for

## Context

ADR-0207 made the accounting day an owned state machine in ledger-service
(`OPEN → CUTOFF → TIED_OUT → LOCKED`) and ADR-0078 decided that a close is a
**completeness control** — "every eligible unit closed, or a recorded, queryable exception with a
reason" — rather than an atomic batch. Neither decides *who* the day's close is complete over.
ADR-0207's `AccountingDayScheduler` advances `CUTOFF → TIED_OUT` on one input only: an OK
control-account tie-out recorded after the cutoff. ADR-0096 freezes periods on top of that and
inherits the same blind spot.

Measured on `origin/main` 820d732cb6 (#13167): six services post money on their own calendar —
interest (accrual 01:00, capitalization 02:00 on the 1st), billing (03:00 on the 1st), lending
(interval accrual and provisioning), treasury (hourly accrual), pension (15-minute, hourly and
05:30 sweeps) and pension-fund (own NAV schedule). None reads the accounting day. So a day can tie
out — and later be locked — while the capitalization that belongs to it has not run, and a job that
runs late posts into a day the ledger considers finished. With the day lock in `shadow` that post
lands silently; with it in `enforce` it becomes a 409 that the job logs and nobody sees as a close
problem. The tie-out cannot notice either way: a missing capitalization is a missing *pair* of
legs, and an absent journal balances perfectly.

## Decision

We will make each calendar-driven product service a **declared close participant** of the
accounting day, and make the ledger's close wait for them.

**D1 — The participation record.** For each accounting day it covers, a participant reports exactly
one record to the ledger that owns its books:

- `RAN` with `journalsPosted` (journals it booked into that day) and `itemsProcessed` (its own unit
  of work — accruals, invoices, loans);
- `SKIPPED` with a mandatory reason (nothing was due, the job is switched off);
- `REFUSED` with a mandatory reason — the job had work for the day and the ledger refused it
  because the day no longer accepts postings (D4).

The record is path-addressed — `PUT /api/v1/ledger/accounting-days/{date}/participants/{name}` —
so a re-report replaces the previous record (ADR-0294's natural-key idiom; a participant that
re-runs after a partial failure reports the new totals). The record carries the reporting principal
and time; the ledger keeps every report as an append-only history row and the latest as the day's
answer.

**D2 — Participants are declared, and bound to an identity.** The ledger's configuration lists the
expected participants and, for each, the one machine principal allowed to report for it
(`openbank.ledger.close.participants.<name>.principal`). A report from any other principal is 403:
interest-service's token cannot report billing as complete. Authorization is the existing
`ledger.create` grant each participant already holds to post journals (`ledger_rest_ext.rego`),
narrowed by that binding, so no new OPA action or bundle restamp is needed.
`rules.yaml: close_participants` is the source the declaration is checked against.

**D3 — `CUTOFF → TIED_OUT` waits for participants, or for an operator.** The scheduler advances a
CUTOFF day only when the tie-out is OK **and** every declared participant has a record for the day.
A participant that has not reported holds the day in CUTOFF, where ADR-0207's stuck-CUTOFF gauge
and alert already page. An operator may **waive** a missing participant
(`POST …/participants/{name}/waive`, `ledger.approve`, mandatory reason), which records an explicit
`WAIVED` row with the operator's identity — the ADR-0078 "recorded exception with a reason", never
a silent pass. The check ships in `shadow` (`openbank.ledger.close.participants-mode`), counting the
days it *would* have held, for the same reason ADR-0207 shipped the day lock that way.

**D4 — A refused post is a participation outcome.** Before posting, a participant asks the ledger
once per run for the target day's state (one read per job run, not per posting — the hot-path
concern of ADR-0207 D4 does not apply). If the day no longer accepts postings, or the ledger
answers a posting with the closed-day conflict, the job does not post, reports `REFUSED` with the
day's state as the reason, and increments `openbank_close_participation_reports_total{outcome="refused"}`.
A report arriving for a day already `TIED_OUT` or `LOCKED` is stored and flagged `late`, and never
moves the day.

**D5 — A shared primitive.** `openbank-libs-runtime` carries the participant side
(`CloseParticipationReporter`, the record type, the metric) so the six participants differ only in
what they count. pension-fund reports to its own ledger deployment (ADR-0337), which runs the same
code with its own participant list.

## Alternatives considered

- **Publish participation as a Kafka event the ledger consumes.** Matches ADR-0207 D4's
  "published, not polled" wording. Rejected: ledger-service has no incoming channel today, so this
  adds its first consumer group, six write ACLs, a DLQ per ADR-0078's fail-loud rule and a
  `KafkaTopic`; and SmallRye's default `failure-strategy: fail` halts the channel, which would
  silently hold every subsequent day in CUTOFF. ADR-0207 D4's objection is a synchronous call *per
  posting*; one report per job run over the client each participant already uses to post journals
  is not that, and a lost report fails safe — the day waits and pages.
- **Have the ledger pull each participant's status at close time.** No participant change needed.
  Rejected: it makes ledger-service depend on six services' availability and APIs to close a day,
  the reverse of the dependency direction every posting already has.
- **Infer completeness from the journals themselves (expected journal count per day).** Rejected:
  the ledger cannot know how many accruals or invoices were due; only the product service can, so
  only it can say "nothing was due" (SKIPPED) as opposed to "did not run".
- **Block forever on a missing participant, no waiver.** Rejected by ADR-0078 D2's poison-unit
  argument: one broken job would hold every day's close for every other participant.

## Consequences

**Positive**
- "Day tied out" now asserts that every calendar-driven product service either ran, had nothing to
  do, or was explicitly waived by a named operator — provable from rows, not logs.
- A late job's post into a closed day becomes a visible `REFUSED` record on the day-end page and a
  metric, instead of a log line.

**Negative**
- One more reason a day sits in CUTOFF. Mitigated by shadow mode first, the existing stuck-CUTOFF
  alert, and the waiver.
- Each participant gains a call to the ledger at the end of its run; if the ledger is down the
  report is retried on the next run and the day waits, which is the intended failure direction.

**Neutral**
- No change to the tie-out, the day lock, period freeze or year attestation; the participant check
  sits beside the tie-out condition in the same scheduler step.

**Enforcement.** `check-close-participants.py` (gate `close-participants-declared`, advisory on
arrival) asserts that `rules.yaml: close_participants`, the ledger's configured participant list
and the services that construct a `CloseParticipationReporter` are the same set.

### Delivery check

```bash
python3 .github/scripts/check-close-participants.py      # exit 0, "6 participants declared, 6 wired"
git grep -l 'CloseParticipationReporter' -- '*/src/main/**.kt' | cut -d/ -f1 | sort -u
# expected: interest, billing, lending, treasury, pension, pension-fund services (+ libs-runtime)
```

Live: `select participant, count(*) from ledger_close_participation group by 1` lists every
declared participant with roughly one row per day since it was wired.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA:    the change adds a state that can hold the close; its detection (stuck-CUTOFF alert) and
           recovery (operator waiver with identity and reason) ship with it.
- GDPR:    not applicable — participation records carry counts and service names, no personal data.
- PSD2:    not applicable — no payment-initiation or account-information API changes.
- CNB:     bears on the completeness of the accounting records that feed regulatory returns; no
           requirement number is cited because none was consulted.

## Delivery

Increment 1 (#13167, first PR): the libs-runtime primitive, the ledger participation table,
endpoints, completeness check in shadow mode, interest-service as the first participant, and the
admin-ui day-end page listing participants. The other five participants follow one PR each.

## References

- ADR-0078 — accounting close model, completeness over atomicity.
- ADR-0207 — accounting day state machine.
- ADR-0294 — path-addressed natural-key idempotency for ledger close-cycle POSTs.
- ADR-0337 — pension company books as a separate ledger deployment.
- Issue #13167.
