# Ongoing monitoring of approved business cases — openbank-kyb-service

The entity and its related parties are screened when a business case is approved. After that, a
sanctions or PEP list can change. This page describes how the service keeps approved cases under
monitoring by `openbank-sanctions-service` and what happens when a match is reported (ADR-0376).

The feature is off by default: `openbank.kyb.monitoring.enabled=false`.

## Who is monitored

Every current screened subject of an approved case: the entity, each representative the register
lists, each reportable beneficial owner and each person named in the declarations. A case is
approved while its status is `SIGNED` or `ACTIVE`. Nobody is monitored for a case in any other
status. A monitoring review does not change the status, so it never ends monitoring.

Each subject has a reference. The onboarding screen sends it as `subjectId` and the registry is
keyed by it, so a clearance an analyst records in sanctions-service applies to monitoring as well.

- The entity: its party id. An entity is not screened before it has a party.
- A person screened by this release or later: a random UUID. It is not derived from the name.
- A person screened by an earlier release: the id that screen used (`kyb-case:<case>:<key>`). The
  check and its clearance in sanctions-service are stored under that id, so it is kept.

A reference never changes. A subject that leaves the case is kept, marked `current: false`, with
its reference. If the ownership register cannot be reached, no subject is treated as having left.

## How a registration is delivered

1. Every write of an approved or ended case updates the table `kyb_monitored_subjects` in the same
   transaction, with one SQL statement. A
   subject that must be monitored gets a row with the desired state `REGISTERED`; a subject of a
   case that ended, or one that left the case, gets `WITHDRAWN`. An approval therefore cannot commit
   without its registrations being owed.
2. A scheduled relay runs every 30 seconds. It sends `PUT` or `DELETE
   /api/v3/sanctions/monitored-subjects/{subjectRef}` for each row that is owed, as the service's
   own client `openbank-kyb`. The body carries the entity type and the name, read from the case at
   that moment.
3. The result of a call decides what happens next:
   - Accepted: the row is `SYNCED`.
   - No answer, a timeout, 5xx, 408 or 429: the row stays `PENDING` and is retried after 30 seconds,
     then with a doubling delay up to one hour, then hourly. There is no retry limit.
   - 401 or 403: the registry does not accept the caller. The row stays `PENDING` and is retried the
     same way. A critical alert fires.
   - Any other 4xx: the registry refuses this subject. The row becomes `REFUSED` and is not retried
     until the case changes or the row is re-asserted. The subject is approved and not monitored.
     The operator view of the case shows `monitoringState: REFUSED` on the subject.

The table holds references and delivery state. It holds no name.

## Repair

- **Cases approved before this feature.** Each run finds approved cases that have a screening and
  no rows, gives their subjects references and registers them.
- **A registry that lost its state.** A confirmed or refused registration is sent again after
  `openbank.kyb.monitoring.reassert-after` (default 7 days). Registration is idempotent. To
  re-register every subject sooner, lower this value, wait until
  `openbank_kyb_monitoring_registrations_pending` returns to 0, then restore it.

## What a finding does

The service consumes `openbank.sanctions.screening.event` and reads the event type from the
`ce-type` Kafka header. A `MONITORED_SUBJECT_MATCHED` event whose `ownerService` is `openbank-kyb`
is applied to the case that holds the reference:

- The finding is stored on the screened subject, with the list, the list entry reference, the
  score and how it was detected. A PEP match sets the PEP fact. Any other kind is stored as a
  sanctions-list match.
- The subject's disposition becomes `OPEN`. An earlier decision is kept as history.
- A monitoring review opens on the case (`monitoringReview.openedAt`).

The status of the case does not change. It stays `SIGNED` or `ACTIVE`, `updatedAt` does not move
and no lifecycle event is published. The customer and every other service read the same case as
before. `GET /api/v1/kyb/cases?status=MANUAL_REVIEW` also returns approved cases with an open
monitoring review, so operators find them in the existing review queue.

An operator then uses the existing routes:

1. Decide the finding: `POST /api/v1/kyb/cases/{id}/screening/findings/{subjectKey}/disposition`.
2. If no sanctions finding is confirmed, close the review:
   `POST /api/v1/kyb/cases/{id}/review/resolve`. Nothing else changes.
3. If the relationship has to end, call `POST /api/v1/kyb/cases/{id}/reject`. On a case with a
   monitoring review this does not reject the case. It records that an exit is required
   (`monitoringReview.exitRequiredAt` and `exitReason`) and publishes
   `BUSINESS_RELATIONSHIP_EXIT_REQUIRED` once.

After an exit is required the case keeps its status and stays monitored. No service acts on the
event yet. The alert `KybMonitoringExitRequired` fires until the exit is carried out by hand.

The same finding delivered twice changes nothing: its id is already on the case.

A record typed as a finding without a usable `ownerService`, `findingId` or `subjectRef` is counted
as `malformed` and sent to the dead-letter topic `openbank.dlq.kyb.sanctions-monitoring-in`.

Findings are handled whether the switch is on or off. The switch only stops the relay.

## Metrics

| Metric | Meaning |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 or 0: the switch |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | relay attempts: `registered`, `withdrawn`, `failed`, `unauthorised`, `refused` |
| `openbank_kyb_monitoring_registrations_refused` | subjects the registry refuses: approved and not monitored |
| `openbank_kyb_monitoring_exits_required` | approved cases whose relationship has to end |
| `openbank_kyb_monitoring_registrations_pending` | rows still owed to the registry |
| `openbank_kyb_monitoring_registrations_oldest_pending_age_seconds` | age of the oldest owed row |
| `openbank_kyb_monitoring_subjects_registered` | rows the registry has confirmed |
| `openbank_kyb_monitoring_findings_total{outcome}` | records on the findings channel by result |
| `openbank_kyb_monitoring_reviews_open` | approved cases under a monitoring review |
| `openbank_kyb_monitoring_reviews_oldest_open_age_seconds` | age of the oldest such review |
| `openbank_kyb_monitoring_cases_unscreened` | approved cases with no screening, which cannot be registered |
| `openbank_kyb_monitoring_runs_total{outcome}` | relay runs: `completed`, `disabled`, `failed` |

The gauges are read from the database by the scheduled run. They read NaN until the first run.

`registered` means the registry accepted the subject. The registry evaluates it on its own sweep.

Outcomes of `openbank_kyb_monitoring_findings_total`: `review_opened`, `duplicate`,
`not_monitored`, `unknown_subject`, `foreign_owner`, `ignored`, `malformed`, `unrecognised`.

## Alerts

- `KybMonitoringRegistrationStuck` (critical): the oldest owed row is older than 30 minutes while
  the switch is on.
- `KybMonitoringReviewUnresolved` (warning): a monitoring review has been open for a day.
- `KybMonitoringFindingNotRouted` (warning): a record had the outcome `unrecognised`, `malformed`,
  `unknown_subject` or `not_monitored`.
- `KybMonitoringRegistryUnauthorised` (critical): the registry answered 401 or 403.
- `KybMonitoringSubjectRefused` (warning): the registry refuses at least one subject.
- `KybMonitoringApprovedCaseUnscreened` (warning): an approved case has no screening while the
  switch is on.
- `KybMonitoringExitRequired` (critical): an exit is required and not yet carried out.
- `KybMonitoringFindingDeadLettered` (critical): a finding reached the dead-letter topic.

## Switching it on

1. Deploy the image. The findings consumer then joins the fallback consumer group.
2. Add the two `sanctions-monitoring-in` lines to `kyb-service-msg-override` in a second change.
   Naming the channel there before the running image has it stops the service at boot.
3. Set `openbank.sanctions.monitoring.enabled=true` in sanctions-service. Without it every
   registration is answered 503.
4. Set `openbank.kyb.screening.enabled=true`. A case approved without screening has no subjects.
5. Set `openbank.kyb.monitoring.enabled=true`.

To switch off, set `openbank.kyb.monitoring.enabled=false`. Subjects already registered stay
registered.

## Limits

- The registry is sent a name and an entity type only. A match is by name.
- The registry's first evaluation of a subject can repeat a finding the onboarding screen already
  raised. Each such finding opens a review.
- The exit of a relationship is not automated. This service records the decision and publishes the
  event.
- A case approved while screening was off is not monitored.
