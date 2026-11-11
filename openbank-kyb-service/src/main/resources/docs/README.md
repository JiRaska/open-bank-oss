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

## Private application listener

The KYB image fixes client certificate authentication at build time. The sandbox listener on
8443 verifies the private CA and still requires an OIDC bearer for API calls. Its ingress admits
only the Delegation workload, whose named bearer can read current statutory evidence
after #15662. Port 8157 continues serving the existing callers. Verify the exact KYB image's
listener posture before applying the TLS certificate and port configuration.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
