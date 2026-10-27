# OpenBank Engagement Service

In-app engagement surfaces (ADR-0220), first infrastructure slice.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Troubleshooting the instrumented test suite

The full suite boots several Quarkus test profiles in one Kover-instrumented test worker. With Gradle's default 512 MiB worker heap, the trace-contract suite exhausted the heap and surfaced as HTTP read timeouts followed by `OutOfMemoryError`. The module's `Test` tasks use a 2 GiB maximum heap so the full suite can retain its coverage data. Keep coverage, request timeouts, and trace assertions enabled when reproducing this failure. This allowance applies only to test workers; it is not a production JVM sizing recommendation.

## Diagnostika instrumentované testovací sady

Úplná sada spouští více testovacích profilů Quarkusu v jednom testovacím procesu s instrumentací Kover. Výchozí halda Gradlu 512 MiB se při trace testech vyčerpala; následovaly timeouty HTTP a `OutOfMemoryError`. Úlohy `Test` tohoto modulu proto používají maximálně 2 GiB haldy, aby se vešla i data pokrytí. Při reprodukci zachovejte měření pokrytí, timeouty požadavků i trace aserce. Nastavení platí pouze pro testovací procesy, nikoli jako doporučení velikosti produkční JVM.

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
