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
anyone. A page times out before its one-hour lease can be reclaimed; settlement requires both the
run and global budget leases to remain owned by the worker.

`openbank.campaign.bulk-admission-per-minute` defaults to `0`, which blocks all bulk admission. Set
it to a measured value from 1 to 500 only after the target environment has a capacity exercise.
The value limits journey starts; it does not establish email/push provider capacity or the load from
customers opening links. A run against the current analytics projection is not a frozen audience
snapshot until preparation completes. `openbank.campaign.max-bulk-audience` defaults to 100000 and
cannot be raised above 100000. The streamed snapshot holds a larger run before starting a journey;
resume rebuilds its partial snapshot. This audience ceiling does not prove that 100000 contacts can
meet a delivery deadline. `openbank.campaign.mass-activation-enabled` separately defaults to false
until the provider, destination and end-to-end capacity controls in ADR-0333 are demonstrated.
`openbank.campaign.mass-completion-deadline-minutes` defaults to `0` and blocks run creation. When
configured, a run with a completed snapshot is held before the next page if even the minimum number
of one-minute admission slots exceeds this deadline. The check is a necessary condition only:
provider delivery, other users of the global slot and destination clicks add time and load.
Mass runs also require positive measured values for
`openbank.campaign.mass-dispatch-capacity-per-minute`,
`openbank.campaign.mass-landing-capacity-rps`,
`openbank.campaign.mass-click-fraction` (0–1) and
`openbank.campaign.mass-click-burst-factor` (at least 1). All default to zero and block run
creation or hold a prepared run. The frozen audience must fit the provider's minimum number of
one-minute slots within the deadline. The estimated click burst from one admission page
(`pageSize / 60 * fraction * burstFactor`) must fit the destination budget. These are necessary
checks against configured evidence; they do not monitor live provider or destination health,
account for multiple messages per journey, or establish that a 100000-person send is safe.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
