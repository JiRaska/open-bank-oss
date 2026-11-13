# OpenBank KYB Service API

Legal-entity verification and business onboarding (ADR-0284).

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
- **A retry replays.** Each screen carries one idempotency key per case and subject.
- **Unavailable is not clear.** A screen that cannot be obtained leaves the subject `PENDING`. The counter `openbank_kyb_screening_subjects_total{outcome}` reports `clear`, `pep`, `potential_hit`, `hit` and `unavailable`.

The switch is off by default. With the switch on, the deployment must supply `SANCTIONS_SERVICE_URL` and a network path to sanctions-service; without them every business case stops at its last signature. While the switch is off the service logs a warning at start and stores no screening, so a case that was not screened never reads as screened.

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
