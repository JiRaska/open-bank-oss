# OpenBank KYB Service API

Legal-entity verification and business onboarding (ADR-0284).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Representation lookup and stored extracts

Representation lookup validates the legal-entity identifier before contacting the registry. Invalid identifiers do not bypass that validation. Stored registry extracts retain the same JSON representation used by onboarding cases; the specialized serializer does not change the API or persisted schema.

## Related-party sanctions and PEP screening

When `openbank.kyb.screening.enabled` is true, the service screens the entity, every representative the register lists and every reportable beneficial owner through the CUSTOMER profile of `openbank-sanctions-service` (`POST /api/v3/sanctions/screen`). A beneficial owner that is a company is screened as an organisation. The screening starts when the declarations are made, because the beneficial owners are known from that step.

- **Approval waits for an answer.** The completing signature and an operator's review resolution are refused with `503 SCREENING_PENDING` while any subject has no answer. Nothing is recorded on a refused attempt except the answers already obtained. The next attempt screens the remaining subjects.
- **A finding goes to a human.** A sanctions hit, a potential hit or a PEP match on any subject moves the case to `MANUAL_REVIEW` after the last signature. A PEP match is stored as a PEP fact on that person and is not stored as a sanctions hit.
- **The customer is not told what was found.** `reviewReason` and the review event carry one neutral flag. The subjects and their outcomes are in `Case.screening`, which is present only on operator responses.
- **A retry replays.** Each screen carries one idempotency key per case and subject.
- **Unavailable is not clear.** A screen that cannot be obtained leaves the subject `PENDING`. The counter `openbank_kyb_screening_subjects_total{outcome}` reports `clear`, `pep`, `potential_hit`, `hit` and `unavailable`.

The switch is off by default. With the switch on, the deployment must supply `SANCTIONS_SERVICE_URL` and a network path to sanctions-service; without them every business case stops at its last signature. While the switch is off the service logs a warning at start and stores no screening, so a case that was not screened never reads as screened.

## Build-time wiring

- **OPA policy decision point.** `openbank.authz.opa-pdp-producer.enabled: true` makes libs-runtime's `OpaPolicyDecisionPointProducer` supply the OPA sidecar client, configured by `opa.url`, `opa.path` and `opa.timeout-ms` (default 500 ms). It is read at build time.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
