# CEÚ daily change overview: alerts

Runbook for the `openbank-ceu-alerts` PrometheusRule
(`openbank-infra/gitops/components/observability/prometheus-rules-ceu.yaml`, ADR-0367, #15292).
The duty: every Czech (CERTIS) working day, by 12:00, the bank submits to ČNB through SDAT the
overview of account changes since the previous working day (report CEUSIFE10).

## How the signals behave

`tax-reporting-service` runs `CeuDailyOverviewScheduler` every 15 minutes. On a working day it
assembles the overview for the window ending yesterday if none exists (actor
`system:ceu-daily-overview`), and from 11:00 Prague publishes
`openbank_ceu_overview_unfiled_past_cutoff = 1` while that overview is not SUBMITTED or
ACKNOWLEDGED. The gauge appears only after the first completed tick. A job that is not running is
`WorkflowLivenessStale{workflow="ceu-daily-overview"}`; a deployment without
`OPENBANK_CEU_REPORTING_START` reads `openbank_workflow_unconfigured{workflow="ceu-daily-overview"} = 1`.

Synthetic (ADR-0252) accounts are never in an overview. Every overview records how many were
dropped (`synthetic_accounts_dropped`, `synthetic_relations_dropped`).

## CeuDailyOverviewNotFiled

Today's overview is not filed and the 12:00 deadline is under an hour away.

1. `GET /api/v1/statutory-returns` (auditor or operator) and find `returnCode=CEUSIFE10` for the
   window ending yesterday (Sunday's date on a Monday).
2. **No row:** assembly failed. The service log names the reason (`[ceu] ... failed validation`
   lists each finding; `cannot be assembled` names the missing source). Fix the data, then
   `POST /api/v1/statutory-returns/assemble` with `catalogueId=cz-ceu`, `returnCode=CEUSIFE10`,
   `entityId=company`, `period=<yyyy-MM-dd>`, or wait for the next tick.
3. **ASSEMBLED:** a second operator (never the assembler) approves:
   `POST /api/v1/statutory-returns/{id}/approve`.
4. **APPROVED:** deliver through SDAT, then `POST /{id}/submitted` with the SDAT reference.
5. When ČNB answers, `POST /{id}/acknowledgement` with `{"accepted":true,"reference":...}`, or
   `{"accepted":false,"reason":...}` (at most 1024 characters). Repeating the identical answer
   returns 200; a different answer is 409. A rejected overview is not a baseline: re-assemble that
   window (`POST /assemble` with the same period creates the next revision, diffed against the last
   ACKNOWLEDGED overview), or the next day's overview carries its changes.
6. **Log says `held (WAITING_FOR_ACKNOWLEDGEMENT)`:** an earlier overview is SUBMITTED with no
   recorded ČNB answer, and no new overview is assembled until it has one. Record the answer
   (step 5); the next tick assembles.
7. **Log says `held (INITIAL_LOAD_NOT_ATTESTED)`:** there is no acknowledged overview, so this
   would be the initial full load. It is refused until `OPENBANK_CEU_INITIAL_LOAD_ATTESTED=true`,
   which must be set only once the register projection is attested complete.

The CEUSIFE10 XML file itself is not rendered by the platform yet: its schema is published only
inside SDAT. Until it is, step 4 is a manual SDAT delivery of the attested content.
