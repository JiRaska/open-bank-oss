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
