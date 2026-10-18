# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.

## Mutation testing scope

`pitest` targets `com.openbank.treasury.domain.*`, `com.openbank.treasury.application.usecase.*`
and `com.openbank.treasury.nostro.*`. The nostro reconciliation suite (Mt940Parser, NostroMatcher,
NostroBreakBook) is plain JUnit with no Quarkus boot; excluding it from `targetTests` left every
nostro mutant at NO_COVERAGE by construction and masked the real score. Keep new pure-JUnit suites
inside the target set when they cover production classes in the targeted packages.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
