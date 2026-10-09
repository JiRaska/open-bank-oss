# Tax Reporting Service API

§38d Vyúčtování daně vybírané srážkou podle zvláštní sazby daně — the platform's system of record for the monthly withholding-tax return (ADR-0180).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Catalogue-defined statutory returns

`/api/v1/statutory-returns/catalogues` exposes the versioned jurisdiction catalogue. The new pension ČNB PSP/PEF definitions specify reporting scope, period, deadline, required figures, and arithmetic checks. An operator can assemble a completed period, a different operator can approve its content hash, and a submitted return records the external submission reference. Corrections create another revision. `/breaches` includes due returns that were never assembled when a reporting start and the reporting entities are configured. The hourly deadline sweep publishes the overdue count and a workflow liveness signal only after its database read succeeds.

`/capability` is authoritative for the current deployment. The pension data adapter and regulator wire renderer are presently unavailable: assembly fails rather than inventing figures, and this service does not claim to have transmitted a regulator file. Pension source read models and verified cell-level rendering are tracked separately. The configured reporting entity IDs and reporting start must be supplied before never-assembled pension returns can be monitored.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
