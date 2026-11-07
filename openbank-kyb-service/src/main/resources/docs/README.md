# OpenBank KYB Service API

Legal-entity verification and business onboarding (ADR-0284).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Representation lookup and stored extracts

Representation lookup validates the legal-entity identifier before contacting the registry. Invalid identifiers do not bypass that validation. Stored registry extracts retain the same JSON representation used by onboarding cases; the specialized serializer does not change the API or persisted schema.

## Build-time wiring

- **OPA policy decision point.** `openbank.authz.opa-pdp-producer.enabled: true` makes libs-runtime's `OpaPolicyDecisionPointProducer` supply the OPA sidecar client, configured by `opa.url`, `opa.path` and `opa.timeout-ms` (default 500 ms). It is read at build time.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
