# Ongoing monitoring of approved business cases — openbank-kyb-service

The entity and its related parties are screened when a business case is approved. After that, a
sanctions or PEP list can change. This page describes how the service keeps approved cases under
monitoring by `openbank-sanctions-service` and what happens when a match is reported (ADR-0376).

The feature is off by default: `openbank.kyb.monitoring.enabled=false`. It is one switch. Off, the
service calls nothing and opens no consumer for findings.

## Who is monitored

Every current screened subject of an approved case: the entity, each representative the register
lists, each reportable beneficial owner and each person named in the declarations.

One rule decides it, in one place. A case is monitored while:

- its status is `SIGNED` or `ACTIVE`, or it has a monitoring finding that is undecided or a
  confirmed sanctions match, and
- no exit has been recorded as carried out.

So an applicant who abandons a case, a timer or a rejection ends monitoring only when no finding
holds the case. A case with an open finding or a confirmed sanctions match stays monitored until
an operator records the exit.

If the ownership register cannot be reached, no subject is treated as having left the case.

## The registry reference

The registry is sent a reference for each subject. It is a random UUID that the database generates
when the subject is first owed to the registry. It is not derived from a name or an identifier, it
never changes and it is not shared between cases. The onboarding screen is not changed: it sends
the same subject id and stores the same data as before this feature.

Consequence: a clearance that an analyst records in sanctions-service for the onboarding screen is
stored under the screening id, not under the registry reference. The registry's first evaluation of
a subject can therefore repeat a finding that onboarding already cleared. An operator decides it
again on the case.

## How a registration is delivered

1. Every write of a case that was ever approved updates the table `kyb_monitored_subjects` in the
   same transaction, with one SQL statement. A subject that must be monitored has the desired state
   `REGISTERED`; any other row of the case has `WITHDRAWN`. An approval cannot commit without its
   registrations being owed. A case that was never approved costs no extra statement.
2. A scheduled relay runs every 30 seconds. It takes one owed row at a time under a 60-second lease
   and sends `PUT` or `DELETE /api/v3/sanctions/monitored-subjects/{subjectRef}` as the service's
   own client `openbank-kyb`. The body carries the entity type and the name, read from the case at
   that moment. Two relays cannot send the same row at once, and a result is recorded only if the
   row still holds the same wish and the same lease.
3. The result of a call decides what happens next:
   - Accepted: the row is `SYNCED`.
   - 400 or 422: the registry's validation rejects this subject. The row becomes `REFUSED`. It is
     sent again when the case is next written and at the next re-assertion. The subject is approved
     and not monitored; the operator view shows `monitoringState: REFUSED`.
   - 401 or 403: the registry does not accept the caller. The row stays `PENDING` and is retried.
     A critical alert fires.
   - Anything else (no answer, a timeout, 404, 409, 429, 5xx): the row stays `PENDING` and is
     retried after 30 seconds, then with a doubling delay up to one hour. There is no retry limit.

The table holds the reference, the subject's key inside the case and delivery state. The key of a
person is derived from the name and is personal data. It stays in this database and is not sent to
the registry.

## Repair

- **A case written by the previous release.** Every write by this release records the case version
  it left the rows in step with. The relay finds approved or ended cases where that record differs
  from the case version and writes them again. This covers cases approved before the feature and
  any case that a pod of the previous release wrote during a rolling deploy or after a rollback.
  The previous release cannot remove monitoring data: the references and findings are in tables it
  does not write.
- **A registry that lost its state.** A confirmed or refused registration is sent again after
  `openbank.kyb.monitoring.reassert-after` (default 7 days). Registration is idempotent. To
  re-register every subject sooner, lower this value, wait until
  `openbank_kyb_monitoring_registrations_pending` returns to 0, then restore it.

## What a finding does

The service consumes `openbank.sanctions.screening.event` in the consumer group
`kyb-service-sanctions-monitoring` and reads the event type from the `ce-type` Kafka header. A
`MONITORED_SUBJECT_MATCHED` event whose `ownerService` is `openbank-kyb` is recorded on the case
that holds the reference, in the table `kyb_monitoring_findings`.

Each finding has its own decision. A later finding about the same subject does not reopen or
replace an earlier decision. The same finding delivered twice changes nothing.

The case does not change: `status` stays as it was, `updatedAt` does not move and no lifecycle
event is published. Every route an applicant can call answers the same as before.

Operators work through three routes:

1. `GET /api/v1/kyb/cases?monitoring=open` lists the cases that wait for an operator: a finding to
   decide, or an exit to record. `status=MANUAL_REVIEW` returns `MANUAL_REVIEW` cases only.
2. `POST /api/v1/kyb/cases/{id}/monitoring/findings/{findingId}/disposition` decides one finding:
   `CLEARED_FALSE_POSITIVE` or `CONFIRMED`, with a reason. A decision is made once.
3. `POST /api/v1/kyb/cases/{id}/monitoring/exit/complete` records, with evidence, that the exit has
   been carried out. It is accepted when an exit is owed and no finding is undecided.

A confirmed PEP match is a risk fact. It needs no exit and publishes nothing.

A confirmed match of any other kind means the relationship has to end. The first one sets
`monitoring.exitOwedSince` and publishes `BUSINESS_RELATIONSHIP_EXIT_REQUIRED` once. Nobody sets
that state by hand: it is read from the findings. The case keeps its status and stays monitored.
No service acts on the event yet, so the exit is carried out by hand. Recording it ends monitoring
and withdraws the subjects from the registry.

A record typed as a finding without a usable `ownerService`, `findingId` or `subjectRef` is counted
as `malformed` and sent to the dead-letter topic `openbank.dlq.kyb.sanctions-monitoring-in`.

## Metrics

| Metric | Meaning |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 or 0: the switch |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | relay attempts: `registered`, `withdrawn`, `failed`, `unauthorised`, `refused` |
| `openbank_kyb_monitoring_registrations_pending` | rows still owed to the registry |
| `openbank_kyb_monitoring_registrations_oldest_pending_age_seconds` | age of the oldest owed row |
| `openbank_kyb_monitoring_registrations_refused` | subjects the registry refuses: approved and not monitored |
| `openbank_kyb_monitoring_subjects_registered` | rows the registry has confirmed |
| `openbank_kyb_monitoring_findings_total{outcome}` | records on the findings channel by result |
| `openbank_kyb_monitoring_reviews_open` | cases with an undecided finding |
| `openbank_kyb_monitoring_reviews_oldest_open_age_seconds` | age of the oldest undecided finding |
| `openbank_kyb_monitoring_exits_owed` | cases with a confirmed sanctions match and no recorded exit |
| `openbank_kyb_monitoring_exits_oldest_owed_age_seconds` | age of the oldest such case |
| `openbank_kyb_monitoring_cases_unreconciled` | approved or ended cases whose rows are not in step with the case |
| `openbank_kyb_monitoring_cases_unscreened` | approved cases with no screening, which cannot be registered |
| `openbank_kyb_monitoring_runs_total{outcome}` | relay runs: `completed`, `disabled`, `failed` |

The gauges are read from the database by the scheduled run. They read NaN until the first run.

`registered` means the registry accepted the subject. The registry evaluates it on its own sweep.

Outcomes of `openbank_kyb_monitoring_findings_total`: `review_opened`, `duplicate`,
`not_monitored`, `unknown_subject`, `foreign_owner`, `ignored`, `malformed`, `unrecognised`.

## Alerts

- `KybMonitoringRegistrationStuck` (critical): the oldest owed row is older than 30 minutes while
  the switch is on.
- `KybMonitoringRegistryUnauthorised` (critical): the registry answered 401 or 403.
- `KybMonitoringSubjectRefused` (warning): the registry refuses at least one subject.
- `KybMonitoringCaseUnreconciled` (warning): a case has stayed out of step for 30 minutes while the
  switch is on.
- `KybMonitoringReviewUnresolved` (warning): a finding has been undecided for a day.
- `KybMonitoringExitOwed` (critical): an exit is owed and not yet recorded.
- `KybMonitoringFindingNotRouted` (warning): a record had the outcome `unrecognised`, `malformed`,
  `unknown_subject` or `not_monitored`.
- `KybMonitoringFindingDeadLettered` (critical): a finding reached the dead-letter topic.
- `KybMonitoringApprovedCaseUnscreened` (warning): an approved case has no screening while the
  switch is on.

## Switching it on

The image, the Kafka ACL and the dead-letter topic can be deployed in any order while the switch is
off. The consumer group and the offset policy are in the image, so no override ConfigMap is needed.

1. Set `openbank.sanctions.monitoring.enabled=true` in sanctions-service. Without it every
   registration is answered 503 and stays owed.
2. Set `openbank.kyb.screening.enabled=true`. A case approved without screening has no subjects.
3. Set `openbank.kyb.monitoring.enabled=true`.

To switch off, set `openbank.kyb.monitoring.enabled=false`. Subjects already registered stay
registered, and findings about them wait on the topic until the switch is on again.

A rollback to the previous release needs no data change. The previous release ignores the new
tables and columns. Cases it writes are brought into step when this release runs again.

## Limits

- The registry is sent a name and an entity type only. A match is by name.
- A clearance recorded in sanctions-service for the onboarding screen does not apply to the
  registry reference (see above).
- The exit of a relationship is not automated. This service records the decision, publishes the
  event and records that the exit was carried out.
- A case approved while screening was off is not monitored.
- No operator screen exists in the admin UI yet. The three routes above are the operator surface.
