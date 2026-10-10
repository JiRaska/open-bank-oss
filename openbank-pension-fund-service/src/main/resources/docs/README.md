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
bucketed by valuation date. A period without a published NAV on its exact end date is not reportable;
it must not be presented as zero activity. Positions are stored with newly
calculated NAVs. For older NAVs without recorded positions, portfolio figures
are unknown rather than an empty portfolio. Basis NAV identifiers and a
fingerprint identify the report inputs. This read model supplies figures;
it does not submit a statutory return to the regulator.
For a non-valuation-day period end, the endpoint returns 409 until an approved
as-of/freshness rule defines which published NAV can represent the close.
