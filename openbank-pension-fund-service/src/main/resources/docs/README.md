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

`GET /api/v2/reporting/funds/{fundId}/period-figures` accepts required
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
`POST /api/v2/position-classification-corrections` (`positionId`, `toClass`, `reason`),
then have a different checker approve or reject it via `/{correctionId}/approve`
or `/{correctionId}/reject`. Proposing requires `pension-fund.nav.calculate`; deciding
requires `pension-fund.nav.approve`. The proposer cannot decide their own correction.
These inputs support the reporting read model; they do not submit a statutory return.


### Classification correction retries

The proposal, approval, and rejection POSTs require a nonblank `Idempotency-Key` of at most 128 characters. A key is scoped to the authenticated caller and operation. Retrying the same instruction returns the original response, even after the correction has moved to another state; changed content with the same key returns 409. The replay snapshot and correction commit in one database transaction. Competing approval/rejection requests can commit only one decision.

### Classification decision concurrency

Approving or rejecting a classification correction updates only a correction still in `PROPOSED` state. The transaction must change exactly one row; a competing decision cannot silently overwrite the first decision. The mutation-query API retains the existing conditional update and transaction semantics.


## API version transition

The authoritative contract is v2.0.0. Fund creation requires an explicit published catalog revision, while amendment retains its existing pin. The unchanged v1 operations remain deprecated compatibility routes; only the two legacy fund-definition writes are excluded by the owner-approved draft exception. See README.en.md / README.cs.md for the 180-day sunset, explicit client transition, preserved fee precision and rollback limits. No operational cutover or deployment is authorized by the draft proposal.

### V2 write retries (draft)

Fund creation, NAV calculation/approve/reject, strategy creation and strategy-change
submit/approve/reject/apply require `Idempotency-Key`. A matching retry returns the original
committed status and JSON. Reusing the caller's key for a different path or request returns 409;
an in-flight request also returns 409 and can be retried with that same key. Missing or invalid
keys return 400. The unchanged legacy v1 operations retain their existing behavior.

Migration V7 stores response snapshots atomically with local mutations. Snapshots can contain
audit personal data and have no automatic expiry. Application rollback retains the table and
receipts: dropping them can reopen completed writes. Do not infer successful native transaction
verification from this draft; the new HTTP/JDBC suite must prove receipt-insertion failure rolls
back the business mutation, exclusion across callers, and stable replay after later amendment.
No migration activation, client cutover or deployment is authorized by these source changes.
