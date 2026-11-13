# Ongoing monitoring of approved business cases — openbank-kyb-service

The entity and its related parties are screened when a business case is approved. After that, a
sanctions or PEP list can change. This page describes how the service keeps approved cases under
monitoring by `openbank-sanctions-service` and what happens when a match is reported (ADR-0376).

The feature is off by default: `openbank.kyb.monitoring.enabled=false`.

## Who is monitored

Every current screened subject of an approved case: the entity, each representative the register
lists, each reportable beneficial owner and each person named in the declarations. A case is
approved while its status is `SIGNED` or `ACTIVE`, and while it is in `MANUAL_REVIEW` because of a
monitoring finding. Nobody is monitored for a case in any other status.

Each subject has an opaque reference: the entity's party id, or a random UUID for everyone else.
The onboarding screen sends the same value as `subjectId`, so a clearance an analyst records in
sanctions-service applies to monitoring as well. The reference is not derived from the name.

## How a registration is delivered

1. Every write of a case updates the table `kyb_monitored_subjects` in the same transaction. A
   subject that must be monitored gets a row with the desired state `REGISTERED`; a subject of a
   case that ended, or one that left the case, gets `WITHDRAWN`. An approval therefore cannot commit
   without its registrations being owed.
2. A scheduled relay runs every 30 seconds. It sends `PUT` or `DELETE
   /api/v3/sanctions/monitored-subjects/{subjectRef}` for each row that is owed, as the service's
   own client `openbank-kyb`. The body carries the entity type and the name, read from the case at
   that moment.
3. A refused or failed call is retried: after 30 seconds, then with a doubling delay up to one
   hour, then hourly. There is no retry limit.

The table holds references and delivery state. It holds no name.

## Repair

- **Cases approved before this feature.** Each run finds approved cases that have a screening and
  no rows, gives their subjects references and registers them.
- **A registry that lost its state.** A confirmed registration is sent again after
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
- The case moves to `MANUAL_REVIEW`. The status it had is kept in `monitoringReview.from`.

An operator then uses the existing routes: decide the finding
(`POST /api/v1/kyb/cases/{id}/screening/findings/{subjectKey}/disposition`), then resolve the
review (`POST /api/v1/kyb/cases/{id}/review/resolve`) or reject the case. Resolving returns the
case to `SIGNED`, or to `ACTIVE` when the entity party is active. Resolving is refused while a
finding is open and after a sanctions finding is confirmed. No mandate is granted again and no
lifecycle event is repeated.

The same finding delivered twice changes nothing: its id is already on the case.

The customer sees no change. On customer responses `status` keeps the value the case was approved
in, and `reviewReason`, `screening` and `monitoringReview` are null. No lifecycle event is
published for the review. The applicant cannot abandon the case during the review, and no idle
timer abandons it.

Findings are handled whether the switch is on or off. The switch only stops the relay.

## Metrics

| Metric | Meaning |
|---|---|
| `openbank_kyb_monitoring_enabled` | 1 or 0: the switch |
| `openbank_kyb_monitoring_registrations_total{outcome,reason}` | relay attempts: `registered`, `withdrawn`, `failed` |
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
- `KybMonitoringFindingNotRouted` (warning): a record was acked with the outcome `unrecognised`,
  `unknown_subject` or `not_monitored`.

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
- A subject screened before references existed is given a new reference. A clearance recorded
  under its old `subjectId` does not apply to monitoring.
- A case approved while screening was off is not monitored.
