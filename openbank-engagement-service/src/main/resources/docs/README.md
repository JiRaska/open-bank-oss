# OpenBank Engagement Service

In-app engagement surfaces (ADR-0220), first infrastructure slice.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Test JVM heap

`build.gradle.kts` gives the test JVM a 2g heap. The test suite starts Quarkus three times in one JVM, alongside Testcontainers and Pact. On the 512m Gradle default, the Kover coverage agent sometimes ran out of memory while writing `build/kover/bin-reports/test.ic` at JVM shutdown. When that happens, the agent records the error in `coverage-error.log` in the same directory and leaves a truncated `test.ic`, and the JVM still exits successfully. The heap setting affects tests only. It does not change the service image.
