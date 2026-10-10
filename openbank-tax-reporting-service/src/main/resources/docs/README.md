# Tax Reporting Service API

§38d Vyúčtování daně vybírané srážkou podle zvláštní sazby daně — the platform's system of record for the monthly withholding-tax return (ADR-0180).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The filing API lists monthly periods, their remittances, and overdue returns. An operator assembles a period before a different operator records it as filed. The export-capability endpoint reports whether EPO XML rendering is available; when it is unavailable, assembling a return does not submit it. Filing still requires an external submission and a recorded reference.

## Access and operations

Bearer tokens are validated against the identity provider. Reads and state changes also require the corresponding OPA decisions; assembling and recording a filing require a human operator. A service account with an operator role cannot perform those state changes. The application uses a PostgreSQL datasource and applies its Flyway migrations at startup. Its management port serves health and metrics separately from the filing API.

Withholding-remitted events feed the monthly totals. Events with an unexpected type are ignored. Events that cannot be decoded are counted as malformed and acknowledged; they do not enter the dead-letter topic. If a valid remittance cannot be recorded, the write is retried and a persistent failure is sent to the configured dead-letter topic. Both the malformed count and dead-letter backlog need investigation before filing: an affected remittance is absent from the period total until corrected or replayed. The source consumer starts at the latest offset and is not a historical backfill mechanism. A successful application rollout by itself does not prove that a return is complete or submitted.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
