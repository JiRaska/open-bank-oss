# OpenBank FINREP / COREP Service

Supervisory financial and prudential reporting (ADR-0097): renders EBA FINREP templates (F01.01 Assets, F01.02 Liabilities, F01.03 Equity, F02.00 Profit & Loss) and the COREP C 01.00 Own Funds template from openbank-ledger-service's GL trial balance.

F01 and COREP stock cells read the ledger's cumulative closing balance for the report month. F02 profit and loss reads movements from January 1 through the report month, resetting at each calendar year. A frozen F02 render requires an attested close for every month in that range; a working preview reads the live journal only through the exact requested date. A date inside a month cannot request frozen F02 evidence.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.


## Balance-sheet stock and period movements

FINREP F01.01, F01.02 and F01.03, and COREP C01.00, read the cumulative ledger balance at the end of the month containing `asOf`. With `FROZEN` evidence, the source is `GET /api/v1/ledger/periods/MONTH/{asOf}/frozen-closing-balance`; `LIVE_PREVIEW` uses the corresponding `/closing-balance` endpoint.

The frozen closing balance sums hash-verified `FROZEN`/`LINES_V1` monthly evidence through the reporting month. Ledger also compares those amounts with the cumulative journal totals by account and currency. A missing, draft or legacy `HASH_ONLY` target close, a failed included-snapshot hash check, or a mismatch against the cumulative journal is refused with 409 rather than replaced by live numbers. Earlier months with postings must therefore be closed before the frozen stock report can be produced.

F02.00 follows a separate source: `/frozen-trial-balance` for frozen evidence and `/trial-balance` for live preview. These return the selected month's movements. The current F02 implementation does not assemble calendar-year-to-date movements. A cumulative closing balance must not be substituted for this flow source.

Use `LIVE_PREVIEW` to inspect current books; it does not establish frozen reporting evidence. Follow upstream close and reconciliation failures before retrying a refused frozen report.

## GL classification

Every GL account with a balance must be in the `FinrepCoverage` allowlist; an unlisted code makes the return report `UNCLASSIFIED_GL_ACCOUNT` instead of silently dropping the amount. The treasury and money-market chart (ADR-0315) is classified as follows:

| GL | Meaning | Template rows |
|---|---|---|
| 1001, 1002 | Nostro accounts at correspondent banks | F01.01 r0010 and r0040 (other demand deposits). A credit balance (overdraft) per ledger line is excluded here and reported in F01.02 r0150, r0120, r0110 and r0300; F01.01 r0380 adds it back |
| 1510 | ČNB deposit facility | F01.01 r0030 (cash balances at central banks) and therefore r0010; not r0040 |
| 1500, 1501; 1520, 1521 | Placements with credit institutions and their accrued interest receivable | F01.01 r0181, r0183 (loans and advances at amortised cost) |
| 2300, 2301; 2310, 2311; 2320 | Deposits from credit institutions, their accrued interest payable, ČNB lombard borrowing | F01.02 r0110, r0120; 2320 also r0130 (central banks); 2300, 2301, 2310, 2311 and nostro overdrafts also r0150 (credit institutions) |
| 4200, 4201 / 5200, 5201 | Money-market interest income / expense | F02.00 r0010, r0051 / r0090, r0120 |

Accrued interest receivable (1520/1521) is one account shared by interbank placements and the ČNB deposit facility, so the facility's accrued interest is reported with loans and advances rather than with cash. The same holds for accrued interest payable 2310/2311: it is reported in r0150 although part of it belongs to the lombard borrowing (r0130). Both are accepted limitations (#13189): splitting them requires a separate ledger account per counterparty class, which ADR-0341's `regulatoryClass` on the product revision is meant to carry. F01.02 r0130 and r0150 and F01.01 r0030 are emitted without a verified EBA datapoint id; confirm them against the annotated templates before regulatory submission (#13603). XBRL-CSV preflight blocks with `UNVERIFIED_DATAPOINT_ID` while they are emitted.

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
