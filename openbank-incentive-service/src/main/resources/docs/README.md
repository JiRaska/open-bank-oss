# OpenBank Incentive Service

Governed fixed-benefit offers and opaque promo-code reservations.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Customer and offer callers

Customer reservation and release endpoints accept the configured customer-edge service account only when the verified JWT client id, subject and Keycloak service-account username match. The offer read admits the campaign service account under the same binding. An operator role admits a verified interactive user session, but cannot make an unrelated machine token an offer reader.

## Offer creation traces

The service includes the Quarkus OpenTelemetry extension for HTTP and database instrumentation. Collector configuration and access controls determine where production traces are exported; an in-memory test recorder does not establish production collector readiness. Treat database trace attributes as potentially sensitive operational data and apply the deployment's telemetry access and retention controls.

`IncentiveTraceContractIT` exercises a real offer-creation HTTP request and checks that the request, offer INSERT and outbox INSERT share one trace. Its dedicated profile enables the tracing SDK and disables the scheduler and outbox dispatcher to keep unrelated background spans out of the assertion. A test-only in-memory exporter records the runtime-generated spans; Kafka output uses an in-memory connector. This trace check complements the relational outbox checks in `IncentiveRestContractIT` and does not prove delivery to a broker or collector.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
