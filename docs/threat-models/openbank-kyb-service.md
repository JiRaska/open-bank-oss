<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-kyb-service

STRIDE/DFD threat model for legal-entity verification and business onboarding (ADR-0284), per
ADR-0030 D2. Not a money-path service: it mints no value. It mints the entity party and the
mandates that later let a person move that entity's money, and since ADR-0376 it is the service
that decides which business customers stay under sanctions and PEP monitoring.

- **Status:** Draft, first pass. Written with the ongoing-monitoring slice (ADR-0376, kyb side) and
  scoped to the surface that slice adds or leans on: related-party screening, the monitored-subject
  registrations and the findings channel. Section 7 lists what of the service is not modelled yet.
- **Last reviewed:** 2026-11-13
- **Owner:** onboarding/KYC CODEOWNERS
- **Related ADRs:** ADR-0284 (business onboarding case), ADR-0376 (ongoing screening by delta),
  ADR-0256 (perpetual KYC: a trigger opens a case, a human decides), ADR-0003 (transactional
  outbox), ADR-0106 (identifier intent), ADR-0237 (scheduler liveness)

## 1. Scope & assets

Assets protected, in priority order:

1. **The monitoring obligation itself.** For every approved business case, the entity and every
   screened related party must be registered with sanctions-service's monitored-subject registry
   for as long as the relationship lasts. The failure is silent: a customer who is not registered
   looks exactly like a customer nobody has matched.
2. **A finding and its decision.** That a named person or company matched a sanctions, terrorist
   or PEP list entry, and what an operator decided about it (`kyb_cases.screening_json`).
3. **The fact that a review is running.** An approved customer must not be able to learn that
   monitoring matched them or someone related to them (tipping-off).
4. **The case's approval state.** A finding may send a case to review; it must never approve,
   reject or end one by itself.

Not an asset of this slice: the names of the related parties already sit on the case row; the
slice adds no second copy of them (see T5).

## 2. Data-flow diagram (textual)

```
                       +---------------- trust boundary: kyb-service -----------------+
 [customer edge] --1-->| KybResource -> BusinessOnboardingService -> case repository   |
 [operator]      --2-->|   (review, disposition, reject)              |  one tx        |
                       |                                              v                |
                       |                        kyb_cases + kyb_outbox + kyb_monitored_subjects
                       |                                              |                |
                       |  KybMonitoringScheduler -> MonitoringRegistrationService -----+--3--> [sanctions-service]
                       |        (relay: PUT / DELETE, retry with backoff)              |   monitored-subject registry
                       |                                                               |   mTLS + own OIDC client
                       |  MonitoredSubjectMatchedConsumer -> MonitoringFindingService <+--4--- [Kafka]
                       |        (ce-type header, owner check, finding id)              |   openbank.sanctions.screening.event
                       +---------------------------------------------------------------+
```

Flows 1 and 2 exist since ADR-0284. Flow 3 is new: an outbound call that carries a related
party's name and entity type under an opaque reference. Flow 4 is new: an inbound event from a
topic this service shares with every other consumer of sanctions events.

## 3. Authn / Authz

- **Flow 3.** The registry takes the owner of a registration from the client the caller's
  service-account token was issued to, and refuses the fleet's shared client. The call therefore
  goes through `SanctionsMonitoringRestClient`, which selects the service's own named OIDC client
  (`@OidcClientFilter("m2m")`), not the default one the screening client still uses. Transport is
  the private-CA mTLS listener of sanctions-service, configured for the `sanctions-service` REST
  client under `%prod`.
- **Flow 4.** Kafka authentication is the service's own KafkaUser (mTLS), granted `Read` on the
  sanctions screening topic. Authorisation of a finding is in the consumer: the `ownerService` of
  the event must equal the client this service registers as, read by `MonitoringSettings` from
  the OIDC client configuration itself.
- **Operator acts** (decide a finding, resolve a review, reject) are the existing routes of
  `KybResource`, restricted by `kyb_rest_ext.rego` to staff; no route is added. On a case with a
  monitoring review `reject` records a required exit instead of rejecting.

## 4. STRIDE

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | **A missed registration (silent false negative).** An approval commits and its subjects are never registered: a crash between the two, a path to an approved state that forgot the call, or a registry that was down | The desired registrations are written by `MonitoringRegistrationStoreImpl.syncInTransaction` inside the transaction of every write of an approved case (`BusinessOnboardingCaseRepositoryImpl.update`), derived from `BusinessOnboardingCase.monitoredSubjects`. No use case calls the registry; `MonitoringRegistrationService` sends what the store owes and retries a failure that may pass without a retry limit. Cases approved before the store existed are found by `casesToBackfill` and written again | The registry accepting a subject is not the subject being evaluated; the first evaluation is the registry's own sweep. A case approved while related-party screening was switched off carries no subjects and cannot be registered; `KybMonitoringApprovedCaseUnscreened` reports those, nothing repairs them |
| T2 | **The switch is off and reads as on** | `MonitoringRegistrationService` is `@Startup` and logs a warning at boot while disabled; a disabled run is counted under its own `disabled` outcome; the `openbank.kyb.monitoring.enabled` gauge publishes the switch; rows stay owed and are sent when it is turned on | With the switch off nothing alerts on the growing backlog, by design. Turning it on is a deployment decision recorded in the rollout order |
| T3 | **Spoofing: a finding addressed to another service, or forged, moves a business case** | `MonitoredSubjectMatchedConsumer` acts only on a record whose `ce-type` header is the finding type, whose body has the shape of a finding, and whose `ownerService` equals this service's own client; the reference must be one this service registered (`MonitoringRegistrationStore.findBySubjectRef`), and the case must still carry it | The topic has no per-message signature: any principal with `Write` on it can raise a finding for a known reference. The worst outcome is a review an operator closes; a finding cannot change a case's status |
| T4 | **Tampering by replay: one finding delivered many times opens many reviews or reopens a decided finding** | The finding id is stored on the screened subject inside the case row (`ScreeningSubject.monitoringMatched`); a redelivery returns the same aggregate and writes nothing. Two deliveries racing collide on the case's optimistic lock and the loser re-reads | A genuinely new finding for a subject an operator already decided reopens the decision on purpose; the earlier one is kept in `pastDispositions` |
| T5 | **Information disclosure: personal data in a reference, a log line or a second store** | A person screened from this change on is identified by a random UUID from `MonitoringRegistrationService.newSubjectRef`, the entity by its party id. `kyb_monitored_subjects` holds the reference and bookkeeping only; the name sent to the registry is read from the case at send time. The relay logs the row id and a reason code, the consumer a record's position and type | A person screened BEFORE this change keeps the id that screen used, `kyb-case:<case>:<key>`, whose key is a truncated hash of the normalised name. It is kept because the check and any clearance in sanctions-service are stored under it; for that cohort the pseudonym travels in the registry URL and on findings |
| T6 | **Tipping-off: the customer, or a third party, learns a monitoring review is running** | A monitoring review is a field beside the case's status (`MonitoringReview`), not a status: `monitoringMatched`, `findingDisposed`, `monitoringReviewResolved` and `exitRequired` leave `status` and `updatedAt` as they were, so every applicant route, `findOpenByIdentifier`, the idle timers and the lifecycle events read what they read before. `CaseResponse.from` carries `screening` and `monitoringReview` on operator responses only. `MonitoringFindingService` publishes no event for the review | An applicant who abandons a SIGNED case under review ends it exactly as without one, and the open finding ends with the case. `BUSINESS_RELATIONSHIP_EXIT_REQUIRED` is published on the lifecycle topic; a consumer that showed it to the customer would tip them off, and nothing enforces that none does |
| T7 | **A finding nobody reviews** | The case is listed in the existing operator queue (`listByStatus`); `monitoringReviewResolved` refuses while a finding is open and after a sanctions finding is confirmed. The age of the oldest open monitoring review is published from the stored timestamp and alerted on after a day | One operator decides a finding: this service has no four-eyes approval wired for any action |
| T8 | **A confirmed listed customer stays active and stops being monitored** | An approved case cannot be rejected while it has a monitoring review (`BusinessOnboardingCase.rejected` refuses); the operator's act is `exitRequired`, which keeps the status, and so keeps `monitored` true, and publishes `BUSINESS_RELATIONSHIP_EXIT_REQUIRED`. `KybMonitoringExitRequired` fires until the exit is carried out | **No service acts on the event.** Restricting the party, revoking mandates and closing accounts is done by hand. This is a blocker for switching monitoring on |
| T9 | **Denial of service of the review queue: a baseline that repeats onboarding findings** | None in this service. The registry's first evaluation of a subject may report what its onboarding screen already reported, and each such finding opens a review | Not accepted for switch-on: suppressing a baseline finding needs the onboarding screen to store the list entry it matched |
| T10 | **Monitoring evasion by withdrawal, or a withdrawal that removes another case's subject** | A withdrawal is owed only when the case stops being approved or a subject leaves it, by the same rule that owes a registration, and an unavailable ownership lookup makes nobody leave (`CaseScreening.rebuilt`). A reference another approved case still wants is not deleted (`wantedElsewhere`), and after every withdrawal the other cases holding it are sent again at a new generation (`reassertElsewhere`) | A defective or compromised kyb-service can withdraw its own subjects; the registry logs each deregistration with the owning client and nothing alerts on volume (sanctions-service threat model, M4) |
| T11 | **A subject the registry will not take stays unmonitored unnoticed, or a permanent refusal hides an outage** | The adapter classifies each failure (`RegistryFailure`): a refusal of the subject moves the row to `REFUSED`, out of the retry loop and out of the backlog age; `KybMonitoringSubjectRefused` fires and the operator view of the case shows `monitoringState`. A refusal of the caller stays owed and has its own critical alert | A refused subject is approved and not monitored until somebody corrects what the registry rejects |
| T12 | **A poison record halts the channel, or a changed body is acked in silence** | A record typed as a finding that has no usable owner, finding id or subject reference is counted `malformed` and nacked; an infrastructure failure is retried and rethrown. The channel is configured with `failure-strategy: dead-letter-queue` and its own dead-letter topic, which exists as a `KafkaTopic` and is writable by the service's KafkaUser; `KybMonitoringFindingDeadLettered` reads that topic | A dead-lettered finding is a match nobody has reviewed until someone replays it |
| T13 | **Elevation of privilege through the registry call** | The service's own client carries `ROLE_API` only; the registry operations are authorised with the action of the screen, which the same client already performs | The owner binding is as strong as the realm's client separation (sanctions-service threat model, M4) |

## 5. Residual risks accepted in this pass

- The first evaluation of a newly registered subject is the registry's sweep, not an event this
  service observes. Between "accepted" and "evaluated" a subject is registered and not yet screened
  again; the registry publishes its own sweep lag.
- The registry is told a name and an entity type only, because that is what the onboarding screen
  sends. Matches are by name alone and their false-positive rate is that of the onboarding screen.
- The legacy cohort keeps a name-derived id (T5).

Not accepted, and blocking for switching the feature on: the missing exit workflow (T8), the
repeating baseline (T9) and cases approved without screening (T1).

## 6. Rollback

Switch `openbank.kyb.monitoring.enabled` off: the relay stops calling the registry and rows stay
owed. Subjects already registered stay registered and their findings are still handled. Removing
the registrations themselves is a withdrawal per row, described in the rollback note of
`V14__ongoing_monitoring.sql`. Decide open monitoring reviews before rolling the release back.

## 7. Not modelled yet

The rest of the service predates this document and has no STRIDE pass here: register adapters and
manual attestation, the signer invitation tokens, the representation attestation store, the
agreement ceremony with document-service, mandate granting in party-service and the Temporal
timers. Each has design notes in the service's `CLAUDE.md`; none of them is covered by the table
above.

## 8. Change log

- **2026-11-13** — First pass, with ongoing monitoring of approved business cases (ADR-0376, kyb
  side), shipped disabled. New outbound edge to the monitored-subject registry under the service's
  own client, new inbound events from the sanctions screening topic, a new store without personal
  data (`kyb_monitored_subjects`, V14), a new outbound event for an exit an operator requires, and
  the screen's `subjectId` for a newly screened person changed from a name-derived key to a random
  one.
