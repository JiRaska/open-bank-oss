# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.

## Mutation testing scope

`pitest` targets `com.openbank.treasury.domain.*`, `com.openbank.treasury.application.usecase.*`
and `com.openbank.treasury.nostro.*`. The nostro reconciliation suite (Mt940Parser, NostroMatcher,
NostroBreakBook) is plain JUnit with no Quarkus boot; excluding it from `targetTests` left every
nostro mutant at NO_COVERAGE by construction and masked the real score. Keep new pure-JUnit suites
inside the target set when they cover production classes in the targeted packages.

## Shared configuration defaults

This service's `application.yaml` does not declare the reactive datasource pool bounds. They come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml` (idle-timeout 5M, max-lifetime 30M, max-size 20), which is on every service classpath at a lower precedence than the service's own file, and equal what this service used to declare. A value set in this service's `application.yaml` takes precedence.
