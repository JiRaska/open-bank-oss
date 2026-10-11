# OpenBank Pension Fund Service API

Provider side of the pension platform: segregated funds, strategies, NAV and the unit register (ADR-0334).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.


## Period reporting

`GET /api/v1/reporting/funds/{fundId}/period-figures` accepts required
`periodStart` and `periodEnd` dates. It exposes aggregate fund figures for
tax-reporting-service: balance sheet, year-to-date profit and loss, unit
roll-forward, portfolio and flows. The endpoint requires an allowed reporting
role and the `pension-fund.reporting.inspect` authorization policy.

The calculation uses published NAVs and transactions priced at those NAVs,
bucketed by valuation date. A period without a published NAV is not reportable;
it must not be presented as zero activity. Positions are stored with newly
calculated NAVs. For older NAVs without recorded positions, portfolio figures
are unknown rather than an empty portfolio. Basis NAV identifiers and a
fingerprint identify the report inputs. This read model supplies figures;
it does not submit a statutory return to the regulator.

### Reporting P&L and position classification

The period-figures read model includes year-to-date revaluation gains, revaluation
losses, other investment result and management fees. Consumers must check
`profitAndLossYtd.linesUnavailableReason` before treating the individual lines as
reportable; an unavailable breakdown is not a measured zero.

Recorded NAV positions carry an instrument class. Historical positions and feed
entries without a class remain `UNCLASSIFIED`. Propose a correction with
`POST /api/v1/position-classification-corrections` (`positionId`, `toClass`, `reason`),
then have a different checker approve or reject it via `/{correctionId}/approve`
or `/{correctionId}/reject`. Proposing requires `pension-fund.nav.calculate`; deciding
requires `pension-fund.nav.approve`. The proposer cannot decide their own correction.
These inputs support the reporting read model; they do not submit a statutory return.


### Classification correction retries

The proposal, approval, and rejection POSTs require a nonblank `Idempotency-Key` of at most 128 characters. A key is scoped to the authenticated caller and operation. Retrying the same instruction returns the original response, even after the correction has moved to another state; changed content with the same key returns 409. The replay snapshot and correction commit in one database transaction. Competing approval/rejection requests can commit only one decision.
