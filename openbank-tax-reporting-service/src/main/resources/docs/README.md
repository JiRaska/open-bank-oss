# Tax Reporting Service API

§38d Vyúčtování daně vybírané srážkou podle zvláštní sazby daně — the platform's system of record for the monthly withholding-tax return (ADR-0180).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The filing API lists monthly periods, their remittances, and overdue returns. An operator assembles a period before a different operator records it as filed. The export-capability endpoint reports whether EPO XML rendering is available; when it is unavailable, assembling a return does not submit it. Filing still requires an external submission and a recorded reference.

## Access and operations

Bearer tokens are validated against the identity provider. Reads and state changes also require the corresponding OPA decisions; assembling and recording a filing require a human operator. A service account with an operator role cannot perform those state changes. The application uses a PostgreSQL datasource and applies its Flyway migrations at startup. Its management port serves health and metrics separately from the filing API.

Withholding-remitted events feed the monthly totals. Events with an unexpected type are ignored. Target events that cannot be decoded are counted as malformed and sent to the configured dead-letter topic with their original Kafka key, value, and headers. If a valid remittance cannot be recorded, the write is retried and a persistent failure is also sent there. Both the malformed count and dead-letter backlog need investigation before filing: an affected remittance is absent from the period total until corrected or replayed. The source consumer starts at the latest offset and is not a historical backfill mechanism. A successful application rollout by itself does not prove that a return is complete or submitted.

## Catalogue-defined statutory returns

`/api/v1/statutory-returns/catalogues` exposes the versioned jurisdiction catalogue. The new pension ČNB PSP/PEF definitions specify reporting scope, period, deadline, required figures, and arithmetic checks. An operator can assemble a completed period, a different operator can approve its content hash, and a submitted return records the external submission reference. Corrections create another revision. `/breaches` includes due returns that were never assembled when a reporting start and the reporting entities are configured, and treats an overdue outstanding correction as a breach even if an earlier revision was submitted. Without the reporting start or fund roster it returns unavailable rather than zero. The hourly deadline sweep publishes the overdue count and a workflow liveness signal only after the complete check succeeds.

Statutory-return access requires a staff identity as well as the route's role and OPA decision. Inspection uses `tax.statutory-return.inspect`; the policy grants it to permitted staff and refuses service-account principals. Assembly, approval, and submission require `ROLE_OPERATOR` and their respective lifecycle decisions. The application also rejects a service-account actor on all three lifecycle routes, including when OPA enforcement is disabled: an operator role alone does not make a machine a maker, checker, or submitter. This guard checks the principal name and token claims, so a service-account token with a UUID principal name is still refused with HTTP 403. A human operator remains subject to the data-availability checks and the separate maker/checker requirement.

`/capability` is authoritative for the current deployment. The pension data adapter and regulator wire renderer are presently unavailable: assembly fails rather than inventing figures, and this service does not claim to have transmitted a regulator file. Pension source read models and verified cell-level rendering are tracked separately. The configured reporting entity IDs and reporting start must be supplied before never-assembled pension returns can be monitored.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
