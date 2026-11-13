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
  `KybResource`, restricted by `kyb_rest_ext.rego` to staff; no route is added.

## 4. STRIDE

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | **A missed registration (silent false negative).** An approval commits and its subjects are never registered: a crash between the two, a path to an approved state that forgot the call, or a registry that was down | The desired registrations are written by `MonitoringRegistrationStoreImpl.syncInTransaction` inside the transaction of every case write (`BusinessOnboardingCaseRepositoryImpl.update`), derived from `BusinessOnboardingCase.monitoredSubjects`. No use case calls the registry; `MonitoringRegistrationService` sends what the store owes and retries with backoff without a retry limit. Cases approved before the store existed are found by `casesToBackfill` and written again | The registry accepting a subject is not the subject being evaluated; the first evaluation is the registry's own sweep. A case approved while related-party screening was switched off carries no subjects and cannot be registered; `openbank.kyb.monitoring.cases.unscreened` counts those, nothing alerts on it |
| T2 | **The switch is off and reads as on** | `MonitoringRegistrationService` is `@Startup` and logs a warning at boot while disabled; a disabled run is counted under its own `disabled` outcome; the `openbank.kyb.monitoring.enabled` gauge publishes the switch; rows stay owed and are sent when it is turned on | With the switch off nothing alerts on the growing backlog, by design. Turning it on is a deployment decision recorded in the rollout order |
| T3 | **Spoofing: a finding addressed to another service, or forged, moves a business case** | `MonitoredSubjectMatchedConsumer` acts only on a record whose `ce-type` header is the finding type and whose `ownerService` equals this service's own client; the reference must be one this service registered (`MonitoringRegistrationStore.findBySubjectRef`), and the case must still carry it. Everything else is acked and counted under its own outcome | The topic has no per-message signature: any principal with `Write` on it can raise a finding for a known reference. The worst outcome is a review an operator closes, never an approval or a rejection |
| T4 | **Tampering by replay: one finding delivered many times opens many reviews or reopens a decided finding** | The finding id is stored on the screened subject inside the case row (`ScreeningSubject.monitoringMatched`); a redelivery returns the same aggregate and writes nothing. Two deliveries racing collide on the case's optimistic lock and the loser re-reads | A genuinely new finding for a subject an operator already decided reopens the decision on purpose; the earlier one is kept in `pastDispositions` |
| T5 | **Information disclosure: personal data in a reference, a log line or a second store** | A reference is the entity's party id or a random UUID from `MonitoringRegistrationService.newSubjectRef`; it replaced a name-derived key as the screen's `subjectId`. `kyb_monitored_subjects` holds the reference and bookkeeping only; the name sent to the registry is read from the case at send time. The relay logs the row id and a reason code | References given before this change do not exist; a subject screened earlier is given a new reference, so an analyst's clearance recorded in sanctions-service under the old `subjectId` is not honoured by monitoring and the match is raised: the safe direction |
| T6 | **Tipping-off: the customer learns a monitoring review is running** | `CaseResponse.from` reads `applicantStatus` and `applicantReviewReason` for a customer, which keep the status the case was approved in and no reason; `screening` and `monitoringReview` are operator-only. `MonitoringFindingService` publishes no lifecycle event for the review, because kyb's events reach services that talk to the customer. The applicant cannot abandon a case under such a review (`BusinessOnboardingCase.abandoned`) | A case reviewed before activation completes later than it would have; a customer comparing timings could notice. An operator's rejection is published as any rejection is |
| T7 | **Repudiation / a finding nobody reviews** | The case sits in the existing operator queue (`MANUAL_REVIEW`); `reviewResolved` refuses while a finding is open and after a sanctions finding is confirmed; an idle-case timer never abandons such a case (`abandonIfInState`). The age of the oldest open monitoring review is published from the stored timestamp and alerted on after a day | One operator decides a finding: this service has no four-eyes approval wired for any action |
| T8 | **Denial of service of the review queue: a baseline that repeats onboarding findings** | None in this service. The registry's first evaluation of a subject may report what its onboarding screen already reported, and each such finding opens a review | Accepted for now: suppressing a baseline finding on a guess would hide a match against a different entry. Exact de-duplication needs the onboarding screen to store the list entry it matched |
| T9 | **Monitoring evasion by withdrawal** | A withdrawal is owed only when the case stops being monitored or a subject leaves it, by the same rule that owes a registration; a reference shared by another approved case of the same entity is not deleted (`wantedElsewhere`). The store records desired state per case and subject | A defective or compromised kyb-service can withdraw its own subjects; the registry logs each deregistration with the owning client and nothing alerts on volume (sanctions-service threat model, M4) |
| T10 | **A poison record halts the channel** | An undecodable finding is acked and counted `malformed`; an infrastructure failure is retried and rethrown, and the channel is configured with `failure-strategy: dead-letter-queue` and its own dead-letter topic, which exists as a `KafkaTopic` and is writable by the service's KafkaUser | A dead-lettered finding is a match nobody has reviewed until someone drains the topic; the fleet's dead-letter alert covers it, no rule specific to this topic exists |
| T11 | **Elevation of privilege through the registry call** | The service's own client carries `ROLE_API` only; the registry operations are authorised with the action of the screen, which the same client already performs | The owner binding is as strong as the realm's client separation (sanctions-service threat model, M4) |

## 5. Residual risks accepted in this pass

- The first evaluation of a newly registered subject is the registry's sweep, not an event this
  service observes. Between "accepted" and "evaluated" a subject is registered and not yet screened
  again; the registry publishes its own sweep lag.
- A baseline finding may repeat an onboarding finding (T8).
- A case approved with related-party screening switched off is not monitored (T1).
- The registry is told a name and an entity type only, because that is what the onboarding screen
  sends. A date of birth the register holds for a representative is not sent, so matches are by
  name alone and their false-positive rate is that of the onboarding screen.

## 6. Rollback

Switch `openbank.kyb.monitoring.enabled` off: the relay stops calling the registry and rows stay
owed. Subjects already registered stay registered and their findings are still handled. Removing
the registrations themselves is a withdrawal per row, described in the rollback note of
`V14__ongoing_monitoring.sql`.

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
  data (`kyb_monitored_subjects`, V14), and the screen's `subjectId` changed from a name-derived
  key to an opaque reference.
