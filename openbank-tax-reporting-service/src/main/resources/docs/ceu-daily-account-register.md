# CEÚ daily account-register feed — tax-reporting

ADR-0367 pilots the ČNB central register of accounts (CEÚ, report `CEUSIFE10`, methodology
CEU20211201) in this service. Every working day the bank files a change overview of the accounts
it keeps: which accounts were opened, changed or closed, and who holds or may dispose of them. It
is due by 12:00 on the next CERTIS working day. This document explains how the overview is built
and what keeps it honest.

## Register projection

`CeuRegisterEventConsumer` reads three channels: account events, party events and delegation
events. Each one dead-letters to its own topic, `openbank.dlq.tax-reporting.<channel>`. A record
sitting on a DLQ is a fact missing from the register until someone replays it.

Each event becomes an **append-only bitemporal fact** in `ceu_register_fact`:

- `effectiveOn` is the valid time: the Prague business day the change took effect.
- `recordedAt` is the record time: when this service learned of it.

Facts are never updated in place. `RegisterSnapshot.fold` rebuilds the register for a day *as
known at* a given instant. Asking the same question again therefore gives the same answer, even
after a late fact has arrived.

## Synthetic customers are excluded (mandatory)

ADR-0252 synthetic customers must never reach a ČNB return. Two independent signals exclude them:

- The Kafka header `x-openbank-synthetic` taints a fact. If the header cannot be read, the fact
  counts as real (fail-to-real), so a parsing problem can never hide a real account.
- party-service's `SYNTHETIC` classification, kept in `ceu_party_classification`, excludes every
  account that party holds.

Both dropped counts are datapoints of the overview, `synthetic_accounts_dropped` and the per-party
count, so every exclusion is visible in the attested content.

## Daily diff job

`CeuDailyOverviewScheduler` is a `suspend fun` `@Scheduled` job that ticks every 15 minutes
(`openbank.ceu.tick-every`) and is idempotent. On a working day it assembles the overview for the
window ending yesterday, unless a revision for that window already exists. Monday's window covers
Friday to Sunday, and a holiday folds into the next window.

`CeuReturnDataAdapter` diffs that day's register against a baseline:

- The baseline is the latest **ACKNOWLEDGED** overview, re-derived as known at the instant stored
  in its `register_known_at_epoch_ms` datapoint. That instant is truncated to the millisecond and
  the current snapshot is cut at the same truncated instant, so a fact is never in both or neither.
- While the latest filed overview is **SUBMITTED** with no ČNB answer, nothing new is assembled:
  the tick ends `WAITING_FOR_ACKNOWLEDGEMENT` (logged as `held`), and a manual
  `POST /api/v1/statutory-returns/assemble` answers 422. The unfiled gauge stays published.
- No acknowledged predecessor means an **initial full load** (`initial_load = 1`). It is refused
  (`INITIAL_LOAD_NOT_ATTESTED`, 422 on the API) unless `openbank.ceu.initial-load-attested` is
  `true` (`OPENBANK_CEU_INITIAL_LOAD_ATTESTED`, default `false`). Set it only once someone has
  attested that the projection holds the complete register (see Known gaps: there is no roster
  seed).
- A rejected or never-filed predecessor is not a baseline, so its changes roll forward into the
  next overview. A late fact surfaces there too. A **REJECTED** window may be re-assembled through
  the API as a new revision of that window; it is diffed against the same last acknowledged
  baseline. The job itself does not re-assemble a rejected window.

The job is the maker only, acting as `system:ceu-daily-overview`. A human approves the overview
(four-eyes) and submits it through SDAT. ČNB's acknowledgement or rejection is then recorded with
`POST /api/v1/statutory-returns/{id}/acknowledgement`.

The feed stays inert until `openbank.ceu.reporting-start` is set.

## Validation

CEÚ record checks run before anything is stored:

- every account has an account number;
- every open account has a HOLDER;
- an account is not closed before it was opened.

A failing overview stores nothing. The log carries only the number of findings and the rule ids,
never the account numbers; re-assemble through the API to see the full findings.

## Liveness and alerting

- Workflow liveness `ceu-daily-overview`: `WorkflowLivenessStale` fires when the scheduler stops
  succeeding.
- `openbank_workflow_unconfigured{workflow="ceu-daily-overview"}` reads 1 while no reporting start
  is configured.
- `openbank_ceu_overview_unfiled_past_cutoff` reads 1 when today's overview is not filed by 11:00
  Prague. It drives the critical alert `CeuDailyOverviewNotFiled`; the runbook is
  `docs/runbooks/ceu-alerts.md`. The gauge is registered on the first completed tick, so a cold pod
  shows no series rather than a 0 that would read as "filed".

## Known gaps

- The CEUSIFE10 XSD is published only inside SDAT, so the wire file is not rendered
  (`wireFormatVerified: false`). Delivery is manual, and the operator records the reference.
- party-service publishes no beneficial-owner event, so UBO relationships are not projected yet.
- No roster seed exists. An initial load covers only what the consumers have seen since they were
  deployed.
