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
   or PEP list entry, and what an operator decided about it (`kyb_cases.screening_json` for the
   onboarding screen, `kyb_monitoring_findings` for ongoing monitoring).
3. **The fact that a review is running.** An approved customer must not be able to learn that
   monitoring matched them or someone related to them (tipping-off).
4. **The case's approval state.** A finding is recorded beside the case; it must never approve,
   reject or end one by itself.

The names of the related parties already sit on the case row; the slice adds no second copy of a
name. It does add a second copy of each subject's KEY, which for a person is derived from the name
(see T5).

## 2. Data-flow diagram (textual)

```
                       +---------------- trust boundary: kyb-service -----------------+
 [customer edge] --1-->| KybResource -> BusinessOnboardingService -> case repository   |
 [operator]      --2-->|   (decide a finding, record an exit)         |  one tx        |
                       |                                              v                |
                       |   kyb_cases + kyb_outbox + kyb_monitored_subjects + kyb_monitoring_findings
                       |                                              |                |
                       |  KybMonitoringScheduler -> MonitoringRegistrationService -----+--3--> [sanctions-service]
                       |        (relay: lease, PUT / DELETE, retry with backoff)       |   monitored-subject registry
                       |                                                               |   mTLS + own OIDC client
                       |  MonitoredSubjectMatchedConsumer -> MonitoringFindingService <+--4--- [Kafka]
                       |        (ce-type header, owner check, finding id)              |   openbank.sanctions.screening.event
                       +---------------------------------------------------------------+
```

Flows 1 and 2 exist since ADR-0284. Flow 3 is new: an outbound call that carries a related
party's name and entity type under a random reference. Flow 4 is new: an inbound event from a
topic this service shares with every other consumer of sanctions events; the channel is opened only
while the monitoring switch is on.

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
- **Operator acts.** Two routes are added, both restricted by `kyb_rest_ext.rego` to staff and
  both recording the authenticated principal. Deciding a monitoring finding
  (`monitoring/findings/{findingId}/disposition`) is authorised as `kyb.case.review.resolve`, the
  action of deciding an onboarding finding. Recording that an exit was carried out
  (`monitoring/exit/complete`) is authorised as `kyb.case.reject`: it is the bank's decision to end
  a case carried through, granted to the same staff and to no service account. A dedicated action
  would be more exact and needs a line in `rules.yaml`. The existing routes are unchanged.

## 4. STRIDE

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T1 | **A missed registration (silent false negative).** An approval commits and its subjects are never registered: a crash between the two, a path to an approved state that forgot the call, a registry that was down, or a pod of the previous release writing the case | The desired registrations are written by `MonitoringRegistrationStoreImpl.syncInTransaction` inside the transaction of every write of an ever-approved case (`BusinessOnboardingCaseRepositoryImpl.update`), derived from `BusinessOnboardingCase.monitoredSubjects`. No use case calls the registry; `MonitoringRegistrationService` sends what the store owes and retries a failure that may pass without a retry limit. Every such write records the case version it left the rows in step with (`monitoring_synced_version`); a case the previous release wrote differs there and `casesToReconcile` has it written again. `KybMonitoringCaseUnreconciled` reports a repair that does not happen | The registry accepting a subject is not the subject being evaluated; the first evaluation is the registry's own sweep. A case approved while related-party screening was switched off carries no subjects and cannot be registered; `KybMonitoringApprovedCaseUnscreened` reports those, nothing repairs them |
| T2 | **The switch is off and reads as on** | `MonitoringRegistrationService` is `@Startup` and logs a warning at boot while disabled; a disabled run is counted under its own `disabled` outcome; the `openbank.kyb.monitoring.enabled` gauge publishes the switch; rows stay owed and are sent when it is turned on. The findings channel follows the same switch, so there is one state, not two | With the switch off nothing alerts on the growing backlog, by design. Turning it on is a deployment decision recorded in the rollout order |
| T3 | **Spoofing: a finding addressed to another service, or forged, moves a business case** | `MonitoredSubjectMatchedConsumer` acts only on a record whose `ce-type` header is the finding type, whose body has the shape of a finding, and whose `ownerService` equals this service's own client; the reference must be one this service registered (`MonitoringRegistrationStore.findBySubjectRef`, unique), and the case must still be monitored | The topic has no per-message signature: any principal with `Write` on it can raise a finding for a known reference. The worst outcome is a finding an operator clears; a finding cannot change a case's status |
| T4 | **Tampering by replay or by a later finding: a decision is reopened or overwritten** | A finding is a row keyed by case and finding id (`kyb_monitoring_findings`), with its own decision, and that table is the only place a finding lives: the case is loaded from it. `CaseMonitoring.recorded` ignores an id it holds and `decided` refuses a finding that is not open. The write stores every finding of the case or fails the whole case write: a stored decision is never replaced by a different one, and a finding id reported for two cases is two rows. A later finding about the same subject is another row and cannot touch an earlier decision, so a confirmed sanctions match stays confirmed whatever follows. Two deliveries racing collide on the case's optimistic lock and the loser re-reads | None known |
| T5 | **Information disclosure: personal data in a reference, a log line or a second store** | The registry reference is a random UUID the database generates (`subject_ref DEFAULT gen_random_uuid()`), one per case and subject, for the entity and for a person alike; nothing derived from a name is sent as an identifier. The name sent in the body is read from the case at send time. The relay logs the row id and a reason code, the consumer a record's position and type. The onboarding screen is unchanged | `kyb_monitored_subjects.subject_key` and `kyb_monitoring_findings.subject_key` hold the subject's key inside the case; for a person it is a truncated hash of the normalised name, which is personal data. It is the same value the case row already holds, in the same database, and it never leaves it |
| T6 | **Tipping-off: the customer, or a third party, learns that monitoring matched** | Monitoring state is not on the case's status and is not set by anyone: findings are rows, and "under review" and "exit owed" are read off them (`CaseMonitoring`). No monitoring transition touches `status` or `updatedAt`, so every applicant route, `findOpenByIdentifier`, the idle timers and the lifecycle events read what they read before; `KybOngoingMonitoringIT` compares status code, headers and body of every applicant route across the phases. `CaseResponse.from` carries `screening` and `monitoring` on operator responses only. Recording a finding publishes no event | `BUSINESS_RELATIONSHIP_EXIT_REQUIRED` is published on the lifecycle topic; a consumer that showed it to the customer would tip them off, and nothing enforces that none does |
| T7 | **A finding nobody reviews** | Cases held by monitoring have their own operator queue (`GET /cases?monitoring=open`, `listMonitoringHolds`). The age of the oldest undecided finding is published from the stored timestamp and alerted on after a day | One operator decides a finding: this service has no four-eyes approval wired for any action. No operator screen exists in the admin UI yet; the queue is an API |
| T8 | **A confirmed listed customer stops being monitored, or an applicant ends monitoring** | `BusinessOnboardingCase.monitored` is the one rule: a case with an undecided finding or a confirmed sanctions match is monitored whatever its status, until an operator records the exit (`exitCompleted`). So abandoning, an idle timer and a rejection change the status as before and cannot withdraw the subjects. That an exit is owed is read off the findings (`exitOwedSince`); `BUSINESS_RELATIONSHIP_EXIT_REQUIRED` is published once and `KybMonitoringExitOwed` fires until the exit is recorded | **No service acts on the event.** Restricting the party, revoking mandates and closing accounts is done by hand, and the record that it was done is an operator's statement with evidence text. This is a blocker for switching monitoring on |
| T9 | **Denial of service of the review queue: a baseline that repeats onboarding findings** | None in this service. The registry's first evaluation of a subject may report what its onboarding screen already reported, and a clearance recorded for the onboarding screen is stored under another identifier than the registry reference | Not accepted for switch-on: suppressing a baseline finding needs the registry to know the onboarding screen's identifier for the subject, or the onboarding screen to store the list entry it matched |
| T10 | **Monitoring evasion by withdrawal, or a withdrawal that removes another case's subject** | A withdrawal is owed only when the case stops being monitored or a subject leaves it, by the same rule that owes a registration, and an unavailable ownership lookup makes nobody leave (`CaseScreening.rebuilt`). References are never shared between cases, so a withdrawal cannot remove another case's subject. A row is sent by one relay at a time (lease), and a result is recorded only against the wish and the lease it was sent for, so a withdrawal cannot overtake the registration before it | A defective or compromised kyb-service can withdraw its own subjects; the registry logs each deregistration with the owning client and nothing alerts on volume (sanctions-service threat model, M4) |
| T11 | **A subject the registry will not take stays unmonitored unnoticed, or a permanent refusal hides an outage** | The adapter classifies each failure by status in one function (`failureOf`): only 400 and 422 are a refusal of the subject, which moves the row to `REFUSED`, out of the backlog age; `KybMonitoringSubjectRefused` fires and the operator view of the case shows `monitoringState`. A refused row that is still wanted is sent again at every write of its case and at re-assertion. 401 and 403 stay owed and have their own critical alert. Everything else, 404 and 409 included, is retried | A refused subject is approved and not monitored until the registry accepts it |
| T12 | **A poison record halts the channel, or a changed body is acked in silence** | A record typed as a finding that has no usable owner, finding id or subject reference is counted `malformed` and nacked; an infrastructure failure is retried and rethrown. The channel is configured with `failure-strategy: dead-letter-queue` and its own dead-letter topic, which exists as a `KafkaTopic` and is writable by the service's KafkaUser; `KybMonitoringFindingDeadLettered` reads that topic | A dead-lettered finding is a match nobody has reviewed until someone replays it |
| T13 | **Elevation of privilege through the registry call** | The service's own client carries `ROLE_API` only; the registry operations are authorised with the action of the screen, which the same client already performs | The owner binding is as strong as the realm's client separation (sanctions-service threat model, M4) |
| T14 | **A release boundary loses monitoring data.** Pods of two releases run side by side, or the release is rolled back, and the older code rewrites a case | Monitoring data is in tables and columns the previous release does not map: it cannot erase a reference, a finding or a decision by rewriting the case row or its JSON. What it can do is write a case without bringing the rows into step, which T1's version check repairs. The onboarding screen's identifiers and stored shape are unchanged, so the two releases read each other's cases | While only the previous release runs, nothing is registered, withdrawn or repaired; findings wait on the topic |

## 5. Residual risks accepted in this pass

- The first evaluation of a newly registered subject is the registry's sweep, not an event this
  service observes. Between "accepted" and "evaluated" a subject is registered and not yet screened
  again; the registry publishes its own sweep lag.
- The registry is told a name and an entity type only, because that is what the onboarding screen
  sends. Matches are by name alone and their false-positive rate is that of the onboarding screen.
- The subject key in the two monitoring tables is name-derived (T5).

Not accepted, and blocking for switching the feature on: the missing exit workflow (T8), the
repeating baseline and the clearance that does not bind the registry reference (T9), cases approved
without screening (T1) and the missing operator screen (T7).

## 6. Rollback

Switch `openbank.kyb.monitoring.enabled` off: the relay stops calling the registry, the findings
channel closes and rows stay owed. Subjects already registered stay registered; findings about them
wait on the topic. Rolling the release back needs no data change (T14). Removing the registrations
themselves is a withdrawal per row, described in the rollback note of `V14__ongoing_monitoring.sql`.

## 7. Not modelled yet

The rest of the service predates this document and has no STRIDE pass here: register adapters and
manual attestation, the signer invitation tokens, the representation attestation store, the
agreement ceremony with document-service, mandate granting in party-service and the Temporal
timers. Each has design notes in the service's `CLAUDE.md`; none of them is covered by the table
above.

## 8. Change log

- **2026-11-13** — First pass, with ongoing monitoring of approved business cases (ADR-0376, kyb
  side), shipped disabled. New outbound edge to the monitored-subject registry under the service's
  own client, new inbound events from the sanctions screening topic, two new stores
  (`kyb_monitored_subjects`, `kyb_monitoring_findings`, V14) that hold a name-derived subject key
  and no name, two operator routes, and a new outbound event for an exit a confirmed match
  requires. The onboarding screen is unchanged.
