# openbank-control-liveness-sentinel

Checks whether declared control workflows have run successfully within their expected intervals.

## Interface

This module has no committed OpenAPI contract. Its endpoints and scheduled workflows are internal; consult the source and the service runbook for their current behavior.

## GitHub proposal egress

The proposal adapter sends requests for the configured GitHub API URL through `SafeHttpClient`. `openbank.egress.allowed-hosts` defaults to `api.github.com`; a different GitHub API host must be explicitly allowed. The client checks resolved addresses, pins a public connection while retaining the hostname for TLS verification, and refuses redirects. Keep the allowlist limited to the intended external API host.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.
