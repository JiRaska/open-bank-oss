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
