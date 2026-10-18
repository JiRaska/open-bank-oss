# openbank-control-liveness-sentinel

Checks whether declared control workflows have run successfully within their expected intervals.

## Interface

This module has no committed OpenAPI contract. Its endpoints and scheduled workflows are internal; consult the source and the service runbook for their current behavior.

## GitHub proposal egress

The proposal adapter sends requests for the configured GitHub API URL through `SafeHttpClient`. `openbank.egress.allowed-hosts` defaults to `api.github.com`; a different GitHub API host must be explicitly allowed. The client checks resolved addresses, pins a public connection while retaining the hostname for TLS verification, and refuses redirects. Keep the allowlist limited to the intended external API host.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Trace continuity into Temporal

The fleet `WorkflowClientOptions` (built by `TemporalClientProducer` in `openbank-libs-temporal`) register an OpenTelemetry context propagator, so the check workflow and its activities run in the trace of whoever started them. `TemporalTracePropagationTest` proves an activity sees the caller's trace id, and that without the propagator it sees none.

## Scheduler heartbeat

The daily check scheduler registers the workflow-liveness heartbeat `liveness-sentinel-check` at startup and records a success only after a check was actually dispatched; a fire skipped because Temporal is disabled does not count. The sentinel cannot watch itself, so `LivenessSentinelHeartbeatAbsent` (in `prometheus-rules-workflow-liveness.yaml`) alerts when that series is missing for 30 minutes.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
