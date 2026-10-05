# Campaign Service API

Campaign management (ADR-0200 / ADR-0209 D3 / ADR-0333): deterministic, versioned segments;
per-enrolment Temporal journeys; consent-gated delivery through notification-service; and bounded,
resumable audience admission.

## Bulk admission

`POST /api/v1/campaigns/{id}/bulk-runs` creates a durable run for an active campaign. The scheduler
admits ordered audience pages under one global database lease; the list and detail endpoints expose
progress and a hold reason. A different operator can resume a held run. Starting a second live run
for the same campaign returns a conflict. The older synchronous enrol endpoint and scheduled sweep
use the same global lease and reject an audience larger than one configured page before admitting
anyone.

`openbank.campaign.bulk-admission-per-minute` defaults to `0`, which blocks all bulk admission. Set
it to a measured value from 1 to 500 only after the target environment has a capacity exercise.
The value limits journey starts; it does not establish email/push provider capacity or the load from
customers opening links. A run against the current analytics projection is not a frozen audience
snapshot. ADR-0333 tracks the additional controls required before whole-audience release.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
