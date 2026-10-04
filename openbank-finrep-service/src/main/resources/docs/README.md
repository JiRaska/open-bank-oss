# OpenBank FINREP / COREP Service

Supervisory financial and prudential reporting (ADR-0097): renders EBA FINREP templates (F01.01 Assets, F01.02 Liabilities, F01.03 Equity, F02.00 Profit & Loss) and the COREP C 01.00 Own Funds template from openbank-ledger-service's GL trial balance.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
