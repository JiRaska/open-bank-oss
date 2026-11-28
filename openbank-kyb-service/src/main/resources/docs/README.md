# OpenBank KYB Service API

Legal-entity verification and business onboarding (ADR-0284).

## Statutory representation contract verification

The Delegation consumer contract checks current and stale revisions and refusal of the shared
workload identity. The KYB provider replays those interactions against its HTTP resource in CI.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Representation lookup and stored extracts

Representation lookup validates the legal-entity identifier before contacting the registry. Invalid identifiers do not bypass that validation. Stored registry extracts retain the same JSON representation used by onboarding cases; the specialized serializer does not change the API or persisted schema.

## Related-party sanctions and PEP screening

When `openbank.kyb.screening.enabled` is true, the service screens the entity, every representative the register lists, every reportable beneficial owner and every person named in the declarations through the CUSTOMER profile of `openbank-sanctions-service` (`POST /api/v3/sanctions/screen`). A beneficial owner that is a company is screened as an organisation. The screening starts when the declarations are made, because the beneficial owners are known from that step.

- **Approval waits for an answer about everyone on the case now.** Before every approval the subject list is rebuilt from the register extract, the current beneficial-owner lookup and the declarations; answers already obtained are kept per subject. The completing signature and an operator's review resolution are refused with `503 SCREENING_PENDING` while any current subject has no answer, and while the beneficial-owner lookup is unavailable. Nothing is recorded on a refused attempt except the answers already obtained. The next attempt screens the remaining subjects.
- **A finding goes to a human, and only an explicit decision settles it.** A sanctions hit, a potential hit or a PEP match on any subject moves the case to `MANUAL_REVIEW` after the last signature. A PEP match is stored as a PEP fact on that person and is not stored as a sanctions hit. Every finding starts with the disposition `OPEN`. An operator sets it to `CLEARED_FALSE_POSITIVE` or `CONFIRMED` through `POST /api/v1/kyb/cases/{id}/screening/findings/{subjectKey}/disposition`; the authenticated operator, the reason and the time are stored. Resolving a review is refused with `422 SCREENING_FINDING_OPEN` while any finding is open, including one the same call discovered, and with `422 SCREENING_FINDING_CONFIRMED` after a sanctions finding was confirmed; such a case can only be rejected. A disposition never moves the case. One operator decides a finding: this service has no four-eyes approval wired for any action.
- **A finding is decided under review, and the case is then approved by review.** A disposition is accepted only while the case is in `MANUAL_REVIEW`. A case that has ever had a finding, whatever was decided, goes to `MANUAL_REVIEW` at its last signature and is approved only by an operator resolving the review.
- **A finding outlives its subject.** A subject that leaves the case is removed from the screening only if it was clear. One with a finding is kept, marked `current: false`, and counts until it is decided.
- **The customer is not told what was found, or that a screen was the cause.** A finding adds nothing to `reviewReason`. On customer responses and on the review event an AML review reads `AML risk review before activation` whatever raised it; the flags from the customer's own answers are in the operator's `reviewReason`. The subjects, their outcomes and the owner source are in `Case.screening`, which is present only on operator responses.
- **A screen carries what the register says, besides the name.** The entity is sent with its registration country, so an entity registered in a listed jurisdiction is caught without a name match. A company that owns shares is sent with its filing country. A person is sent with the date of birth, nationality and country of residence the register lists, so a match on a common name can be confirmed or ruled out. These facts are taken from the register extract each time a subject is screened and are not stored with the screening. Trading names are not sent, because no register adapter provides them yet.
- **Two people with one name are screened apart.** Subjects are keyed by name, so two listings under one name are merged only when they do not contradict each other; listings with a different country, date of birth or nationality are each screened under the same subject, and the worst answer decides it.
- **A retry replays; changed facts are a new screen.** Each screen carries an idempotency key derived from the case, the subject and a digest of everything the screen sends, so a retry of the same screen replays while a screen with changed facts is screened again. A clearance in sanctions-service covers only a screen with the same facts.
- **Unavailable is not clear.** A screen that cannot be obtained leaves the subject `PENDING`. The counter `openbank_kyb_screening_subjects_total{outcome}` reports `clear`, `pep`, `potential_hit`, `hit` and `unavailable`.

The switch is off by default. With the switch on, the deployment must supply `SANCTIONS_SERVICE_URL` and a network path to sanctions-service; without them every business case stops at its last signature. While the switch is off the service logs a warning at start and stores no screening, so a case that was not screened never reads as screened.

## Ongoing monitoring of approved cases

When `openbank.kyb.monitoring.enabled` is true, the service registers the entity and every screened related party of an approved case with the monitored-subject registry of `openbank-sanctions-service`, withdraws them when the case ends, and records each match the registry reports as a finding for an operator to decide. A finding does not change the status of the case. Each subject is registered under a random reference that the database generates. The switch is off by default. See `08-ongoing-monitoring` in this documentation index for delivery, repair, metrics, alerts and how to switch it on.

## Concurrent writes to a case

`kyb_cases.version` is an optimistic lock. Every write of a case states the version it was read at. If the stored row has moved on, nothing is written and the request answers `409 CASE_CONCURRENT_UPDATE`. This holds for every case transition, because they share one repository write. The party-activation event handler reads the case again and re-applies on a conflict, up to three attempts, because nobody retries an event.

## Build-time wiring

- **OPA policy decision point.** `openbank.authz.opa-pdp-producer.enabled: true` makes libs-runtime's `OpaPolicyDecisionPointProducer` supply the OPA sidecar client, configured by `opa.url`, `opa.path` and `opa.timeout-ms` (default 500 ms). It is read at build time.

## Private application listener

The KYB image fixes client certificate authentication at build time. The sandbox listener on
8443 verifies the private CA and still requires an OIDC bearer for API calls. Its ingress admits
only the Delegation workload. Port 8157 continues serving the existing callers.

The mounted certificate is supplied through KYB-specific environment variables. Earlier images
ignore these variables and continue serving 8157 during the rollout. After the certificate is ready,
verify the new image's two-listener posture. Deploy the statutory authority route from #15662 and
verify its policy admits the named Delegation workload before deploying the collection consumer.
The sandbox collection flag stays off while those checks run.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Statutory representation publication

KYB staff can publish a completed case's joint representation evidence when its human
attestation explicitly confirms a joint mode and remains current. The Active cases view
submits the ID of the confirmation shown to the reviewer. The verified humans must have
signed the case's own agreement ceremony. A fixed quorum needs enough verified signers for
the attested count and offices. Joint-all requires all register representatives.
Delegation-service can verify that the exact source attestation remains current, including
before KYB publishes its replacement to Party.

## Operator logs

Operator identities, screening subject identifiers and diagnostic exception text are sanitized before they enter operational logs. Control characters and Unicode line separators are replaced with underscores. Case records and emitted events retain their original values.
