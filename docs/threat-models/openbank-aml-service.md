# Threat model - openbank-aml-service

## Scope

AML screening cases, post-booking transaction monitoring and the suspicious-transaction report (ADR-0371). The service books nothing and sits in no payment path. Its assets are the confidentiality of who is being monitored or reported, the integrity of who decided what, and the completeness of what monitoring saw. STRIDE categories are named per threat.

Trust boundaries: Kafka in (`openbank.party.events`, `openbank.ledger.journal.posted`, `openbank.transactions.transaction.initiated`), Kafka out (`openbank.aml.events`, `openbank.oversight-triggers`), REST in from the operator console and from the payment services that open cases, REST out to account-service and party-service, and the service's own Postgres.

## Tipping-off (information disclosure)

Telling a customer that they are monitored or reported is the worst outcome here. A leak cannot be recalled.

- **A monitoring case or a report reaches a customer-facing surface.** The report and disposition endpoints (`AmlReportResource`, `AmlMonitoringDispositionResource`) admit `ROLE_COMPLIANCE` only, in `@RolesAllowed` and in `aml_rest_ext.rego` (`compliance-aml-monitoring-and-report`, human principals only). `AmlCaseResource` answers 404 for a `TRANSACTION_MONITORING` case to every caller without that role and drops such cases from its list, so the existing case API does not reveal that one exists. No customer-edge or notification contract carries these types. Gap: nothing mechanical stops a future contract from adding one; the planned gate `aml-no-customer-surface` is not built.
- **An operator reads a report through the fleet-wide read grant.** The base policy grants every operator any action ending in `.read` or `.list`. The report and disposition read actions are named `amlReport.inspect` and `amlMonitoring.inspect` so they fall outside that grant; the rego test file holds the negative control that shows a `.read` name would have leaked.
- **Event fan-out.** `AmlReportStateChangedEvent` carries a report id, a state and an instant, and nothing else, because `openbank.aml.events` is read by other services. Accepted limitation: `AmlCaseCreatedEvent` and `AmlCaseStatusChangedEvent` still carry the party id and the alert code for every case type, and party-service, domestic-payment and context-service consume that topic. While evaluation runs in SHADOW no monitoring case is created, so nothing is emitted; this must be closed before case opening is switched on (`openbank.aml.monitoring.evaluation.open-cases`).
- **A machine principal reads monitoring data.** Machine principals are refused by the policy rule even when they hold the compliance role. Accepted limitation: agent-service reads cases through `amlCase.read`; the 404 rule above applies to it, so it sees no monitoring case.
- **Logs and metrics.** Monitoring log lines carry counts, ids of packs and scenario codes on metric tags, never a party id on a metric. Case and report ids appear in logs; the narrative and the filing reference do not.
- **No model call.** The service calls no language model. Any assistance runs in the agent plane under its own controls (ADR-0371).

## Decisions (spoofing, repudiation, elevation of privilege)

- **A caller names someone else as the decider (spoofing).** `UpdateAmlDecisionRequest.decidedBy` is ignored; `AmlCaseResource` takes the decider from the authenticated principal. The same holds for every proposer, approver, drafter and filing officer.
- **One person closes a monitoring case (elevation of privilege).** `AmlCaseService` refuses to close or escalate a `TRANSACTION_MONITORING` case without an approved proposal, so the plain decision endpoint, the sandbox auto-clear (`OnboardingScreening`) and any future job cannot do it. `MonitoringDispositionService` uses the fleet maker-checker primitive, where the approver must differ from the proposer, and `aml_case_disposition_proposal` repeats the rule as a CHECK constraint. Accepted limitation: two accounts held by one person defeat any four-eyes control; that is an identity-governance matter outside this service.
- **A report is filed without a second approver.** `SuspiciousTransactionReport` reaches FILED only from APPROVED, and approval refuses the drafter. The table `aml_str` refuses FILED without an approver, a reference, an instant and a recording officer, and refuses an approver equal to the drafter, whatever the application does.
- **A filing that did not happen (repudiation).** FILED records that a named officer filed the report outside this system, under a reference. The service submits nothing and claims no electronic submission. Accepted limitation: the authenticity of the reference rests on that officer.
- **A decision without a reason anyone can learn from.** Every closing decision takes a code from the versioned outcome vocabulary (`DecisionOutcomes`); an unknown code is refused. The stored label pins the vocabulary revision and hash.
- **Concurrent decisions.** Proposal and report transitions are compare-and-set on the previous state; the loser changes nothing and gets a conflict.

## Monitoring intake and evaluation (tampering, denial of service)

- **A forged or malformed event inflates or hides counts (tampering).** Only principals with a Kafka write ACL on the ledger and transaction topics can publish there. `LedgerBookedMovementConsumer` rejects incomplete or implausible bookings to its dead-letter topic rather than acking them. Accepted limitation: the payload is not signed, so a compromised producer is trusted.
- **A replay double-counts.** A booking is recorded once on its business key (journal entry, account, currency); the claim and the counter increments share one transaction in `MovementIntakeRepositoryImpl`.
- **Monitoring silently sees nothing (denial of service).** Each consumer has its own dead-letter topic; `MovementAttributionScheduler` publishes the uncounted backlog and its age; `MonitoringEvaluationScheduler` records workflow liveness only for a tick that reached every active party. An evaluation that could not run is stored as INCOMPLETE with its reason, never as "nothing found".
- **A pack or scheme is swapped (tampering).** Counter scheme, features, vocabularies and pack are compiled at startup and pinned by content hash (`MonitoringPackLoader`); a mismatch stops the boot. Accepted limitation: the shipped files are synthetic and unsigned. Signature, approval and activation belong to the ADR-0354 pack lifecycle, and case opening stays off until then.
- **Account-service unavailable.** Intake never calls it. Attribution retries; unresolved movements stay uncounted and visible.

## Data

Monitoring tables hold party and account identifiers, amounts and decisions. Classification is `restricted`. Retention overrides erasure for these records (ADR-0118); the period is not asserted in this document.

## Open items before ACTIVE

- Reduce case events for monitoring cases to identifier and state.
- Build the `aml-no-customer-surface` gate.
- Authenticated pack delivery (ADR-0354) instead of files in the image.
