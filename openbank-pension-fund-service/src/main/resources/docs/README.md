# OpenBank Pension Fund Service API

Provider side of the pension platform: segregated funds, strategies, NAV and the unit register (ADR-0334).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The pension service uses the fund and unit register through holdings, transactions, strategies,
and order endpoints. Provider verification tests replay its consumer contract against this service,
including holdings before a NAV is published and requests without a valid service identity.
These tests exercise the provider API; they do not establish that a fund or pension rollout is live.

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
