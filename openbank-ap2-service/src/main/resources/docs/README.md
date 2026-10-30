# OpenBank AP2 Mandate Verifier

Bank-side verification of AP2 (Agent Payments Protocol) signed mandates as an authorization-evidence type (ADR-0193).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Production authentication

`/ap2/*` requires an OIDC bearer token on both the plain HTTP listener and the
mTLS listener. A trusted client certificate authenticates only the TLS
connection and cannot replace the bearer token. Callers using mTLS must supply
both credentials: a trusted certificate without a bearer token receives 401,
while a missing trusted certificate fails the TLS handshake before HTTP.
Mandate verification and the endpoint's authorization checks still apply after
authentication.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
