# CRS/FATCA information-exchange reporting — tax-reporting

ADR-0340 adds the `cz-tax-information-exchange` return catalogue (OECD CRS and FATCA, YEAR
periodicity, 181-day statutory deadline) to this service's catalogue-defined statutory returns
(ADR-0336 lifecycle). This document explains what the new assembly does and the bounds it keeps.

## What assembles

The `CRS` and `FATCA` returns are line-based: one `ReturnLine` per account per reportable
residency country. Lines ride the return's revision machinery — a correction is a new return
revision, and an attested revision's lines stay exactly what was approved and submitted. The
line fields are keyed by the catalogue `lineSchema` (`holder_name`, `party_ref`,
`account_ref`, `residency_country`, `tin`, `currency`, `period_end_balance`,
`interest_credited`); the `tin` may be null for a residency declared without one. The four
datapoints (`reportable_persons`, `reportable_accounts`, `total_interest_credited`,
`total_period_end_balance`) are platform aggregates, not wire figures — mixed-currency totals
are summed as bare numbers and the per-line currency keeps the breakdown honest. Totals are
summed per **distinct account**, never per line: one account with two residency countries
emits two CRS lines but its balance and income count once. An overdrawn account keeps its
negative balance in both the line and the total (only `persons`/`accounts`/`interest` are
non-negative in the catalogue rules).

## Where the figures come from

`CatalogueRoutingReturnDataPort` is the single `ReturnDataPort` bean and routes by catalogue
id; the CRS/FATCA adapter reads, in one snapshot pass:

- the ACTIVE account roster (cursor-paginated, walked to the end — a truncated roster would
  under-report every datapoint; any source failure refuses the whole return with 503, never a
  partial or zero-filled one);
- reportable status and TINs from party-service's dedicated `tax-facts` boundary (fetched once
  per party, cached per assembly; a party without a profile was never self-certified and is
  omitted — the return says so by absence, matching the profile-derived definition of
  REPORTABLE);
- the period-end booked balance from balance-service's statement `asOf` read (no balance row
  for an ACTIVE account means no booked movements — genuinely zero);
- gross interest credited from `observed_income_credit`, fed by the
  `interest.withholding.recorded.v1` consumer on `openbank.interest.accrual.event`. The stored
  row is keyed by `withholding_id`, so a Kafka redelivery is a no-op. Reporting reads income
  events, never the tax decision (ADR-0340 E).

CRS emits every non-CZ residency; FATCA emits the US residency only for parties whose FATCA
status is US_PERSON. Both returns refuse a catalogue they do not own rather than guessing.

## Reading the artefact

`GET /api/v1/statutory-returns/{id}/lines` returns the line-level artefact of a line-based
return; figure-only returns (pension ČNB, ADR-0336) answer with an empty list. The stored
lines are covered by the return `content_hash` alongside the datapoints, so a submitted
revision replays exactly what was attested.
