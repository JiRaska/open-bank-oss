# Tax Reporting Service API

§38d Vyúčtování daně vybírané srážkou podle zvláštní sazby daně — the platform's system of record for the monthly withholding-tax return (ADR-0180).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Catalogue-defined statutory returns

`/api/v1/statutory-returns/catalogues` exposes the versioned jurisdiction catalogue. The new pension ČNB PSP/PEF definitions specify reporting scope, period, deadline, required figures, and arithmetic checks. An operator can assemble a completed period, a different operator can approve its content hash, and a submitted return records the external submission reference. Corrections create another revision. `/breaches` includes due returns that were never assembled when a reporting start and the reporting entities are configured, and treats an overdue outstanding correction as a breach even if an earlier revision was submitted. Without the reporting start or fund roster it returns unavailable rather than zero. The hourly deadline sweep publishes the overdue count and a workflow liveness signal only after the complete check succeeds.

`/capability` is authoritative for the current deployment. Pension figures are read from pension-fund-service's period figures (fund balance sheet, year-to-date P&L, unit roll-forward, portfolio, entitlements, participant counts) and pension-service's participant aggregates (contributions by source, payouts, pensioners), using this service's own machine client. Returns whose figures no platform service owns — the pension company's own balance sheet, P&L, portfolio, capital, organisation and dividends, and fund loans — and any period a source cannot answer fail as unavailable rather than inventing figures. The regulator wire renderer is unavailable, and this service does not claim to have transmitted a regulator file; verified cell-level rendering is tracked separately. The configured reporting entity IDs and reporting start must be supplied before never-assembled pension returns can be monitored.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
