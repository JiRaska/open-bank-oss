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

F02.00 follows a separate, calendar-year-to-date flow source: `GET /api/v1/ledger/periods/MONTH/{asOf}/frozen-year-to-date-trial-balance` for frozen evidence and `/year-to-date-trial-balance` for live preview. Both cover income and expense movements from January 1 of the reporting year through `asOf`, so a report never inherits a prior year's flows. The frozen route sums hash-verified monthly evidence for every month of the year and is refused when any month's evidence is missing or does not match the journal. A cumulative closing balance must not be substituted for this flow source.

Use `LIVE_PREVIEW` to inspect current books; it does not establish frozen reporting evidence. Follow upstream close and reconciliation failures before retrying a refused frozen report.

## GL classification

Every GL account with a balance must be in the `FinrepCoverage` allowlist; an unlisted code makes the return report `UNCLASSIFIED_GL_ACCOUNT` instead of silently dropping the amount. The treasury and money-market chart (ADR-0315) is classified as follows:

| GL | Meaning | Template rows |
|---|---|---|
| 1001, 1002 | Nostro accounts at correspondent banks | F01.01 r0010 and r0040 (other demand deposits). A credit balance (overdraft) per ledger line is excluded here and reported in F01.02 r0120, r0110 and r0300; F01.01 r0380 adds it back |
| 1510 | ČNB deposit facility | F01.01 r0030 (cash balances at central banks) and therefore r0010; not r0040 |
| 1500, 1501; 1520, 1521 | Placements with credit institutions and their accrued interest receivable | F01.01 r0181, r0183 (loans and advances at amortised cost) |
| 2300, 2301; 2310, 2311; 2320 | Deposits from credit institutions, their accrued interest payable, ČNB lombard borrowing | F01.02 r0110 and aggregate deposits r0120; F01.02 provides no counterparty split |
| 4200, 4201 / 5200, 5201 | Money-market interest income / expense | F02.00 r0010, r0051 / r0090, r0120 |

The ADR-0344 instrument chart (ledger V35) and the V26/V34 additions are classified as follows. `LedgerChartCoverageTest` derives the seeded leaf accounts from the ledger migrations and fails when any is neither classified nor declared memo. "Only" means the amount reaches that total but no category row is emitted: the IFRS 9 category rows (F01.01 r0050/r0060/r0080/r0141/r0143/r0182, F01.02 r0010/r0020, F01.03 r0090, F02.00 gains/losses) need datapoint ids verified against the pinned workbook first, and the service emits no unverified coordinate.

| GL | Meaning | Template rows |
|---|---|---|
| 1110-1113 | Scheme settlement balances | F01.01 r0040 and r0010, as 1100-1103 |
| 1600, 1601; 1630, 1631; 1640, 1641 | Securities at amortised cost, unamortised premium/discount, accrued interest | F01.01 r0181 |
| 1610, 1611 | Securities at FVOCI | F01.01 r0380 only |
| 1620, 1621 | Securities at FVTPL | F01.01 r0380 only |
| 1700, 1701; 1670, 1671 | Derivative fair value and accrued interest | F01.01 r0380 only |
| 1650, 1651; 1660, 1661; 1680, 1681; 1685, 1686 | Treasury loans, reverse repo and their accrued interest | F01.01 r0181, r0183 |
| 1690, 1691 | Treasury cash in transit | F01.01 r0360 |
| 2400, 2401; 2410, 2411 | Derivative liabilities and accrued interest | F01.02 r0300 only |
| 2420, 2421; 2430, 2431 | Repo payable and accrued interest | F01.02 r0110, r0120 |
| 3100, 3101 | FVOCI revaluation reserve | F01.03 r0300 only; not in COREP C01.00 |
| 4004-4006 | Fee income per currency | F02.00 r0200 |
| 4220, 4221; 4240, 4241 / 5240, 5241 | Treasury loan and reverse-repo interest income / repo interest expense | F02.00 r0010, r0051 / r0090, r0120 |
| 4210, 4211; 4230, 4231 / 5230, 5231 | Securities and derivative interest income / derivative interest expense | F02.00 r0010 / r0090 only |
| 4300-4311 / 5300-5311 | Realised and unrealised treasury gains / losses | F02.00 r0355 (net) and r0670 |
| 9000-9021 / 9100-9121 | Off-balance memo pairs (forward commitments, collateral received and pledged) | None. Excluded from every F01/F02 figure; counted as governed, not as a data gap |

Accrued interest receivable (1520/1521) is one account shared by interbank placements and the ČNB deposit facility, so the facility's accrued interest is reported with loans and advances rather than with cash. Accrued interest payable 2310/2311 is likewise included in aggregate deposits. F01.02 r0130 is debt securities issued and r0150 is hedge-accounting derivatives, not central-bank or credit-institution deposits; both are zero for the current ledger chart. The datapoint ids for these rows and F01.01 r0030 were verified against the pinned Framework 4.2 / DPM 4.2.1 annotated workbook (#13603).

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
