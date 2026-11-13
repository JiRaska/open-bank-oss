# openbank-devops-agent

Runs development-operations analysis and records reviewable findings.

## Interface

The internal REST contract is published in `openapi.yaml`. Authenticated readers can inspect the current delivery overview and persisted shadow history; sampling and measurement semantics are documented in `08-delivery-shadow.en.md`. Mutating finding and analysis operations remain admin-only.

## GitHub API egress

The metrics and remediation adapters use `SafeHttpClient` for the configured GitHub API URL. `openbank.egress.allowed-hosts` defaults to `api.github.com`; deployments using a different GitHub API host must explicitly list that hostname. The client checks resolved addresses and connects to a pinned public address, keeps the hostname for TLS verification, and does not follow redirects. A refused request does not justify adding an internal address to the allowlist.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
