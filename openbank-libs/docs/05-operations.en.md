# 05 — Operations

## Modules and builds

The root `openbank-libs` module is a compatibility umbrella: it re-exports `openbank-libs-domain` and `openbank-libs-runtime`. New services depend on the module they need. The domain module holds framework-free primitives; the runtime module contains Quarkus adapters and `/q/openbank/docs`.

```bash
./gradlew :openbank-libs-domain:build :openbank-libs-runtime:build :openbank-libs:build
./gradlew :openbank-libs-domain:test :openbank-libs-runtime:test
```

Use the root Gradle build for service integration. The source of truth for Kotlin and Quarkus versions is `openbank-libs/gradle/libs.versions.toml`; the Gradle wrapper defines its own version. Read those files for current values rather than copying a version table into this document.

## Service documentation

Every runnable service using `openbank.quarkus-service` gets a generated `00-build.md` from its own `version.txt` and committed `openapi.yaml` during `processResources`. The generated page is part of the service JAR. Authored chapters belong in `<service>/src/main/resources/docs/` and are packaged alongside it. The service publishes the resulting index and Markdown at `/q/openbank/docs`; the index includes its release version, build time, and Git commit.

The Gradle `verifyServiceDocs` task checks the packaged build facts and runs through `check` in CI. The PR gate requires an authored documentation change when production service inputs change. A generated build page proves provenance and availability; the authored chapters explain behavior and must be reviewed with code.

`openbank-libs` has no running endpoint of its own. Its `docs/` directory is copied into the Admin UI image. That page is therefore a snapshot of the Admin UI build, while service pages come from the running service images.

## Releases and diagnosis

Services use their own `version.txt` and release-please configuration. Do not infer a service release version from the shared library module version. Inspect `/api/v1/info`, `/q/openbank/docs`, and `/q/openbank/docs/_meta` on the deployed service to identify the running version, source commit, and document digest. A document unavailable in Admin UI can mean the service is not deployed or its endpoint is unreachable; it does not prove that the source tree lacks documentation.

## Startup warm-up and readiness (#11890)

Every service built on `openbank-libs-runtime` warms the JVM before it reports ready. After `StartupEvent`, `StartupWarmup` runs on its own thread: Jackson round-trips, a `select 1` on the reactive pool if present, and self-calls to `/api/v1/info` and to `openbank.warmup.protected-path` without a token (a 401 in production, which still exercises the security filters). `WarmupReadinessCheck` stays DOWN until it finishes and reports UP after `openbank.warmup.max-duration` (default 20s) in every case, so Argo Rollouts routes traffic only to a warm pod and never holds one out forever.

Why: in the sandbox every slow request over 48h was the first request after a pod start (1–2 s vs 17–60 ms). Measured first-request median with the warm-up: 967 ms vs 2622 ms without.

Config: `openbank.warmup.enabled` (default true, off in `%test`), `openbank.warmup.max-duration`, `openbank.warmup.json-iterations`, `openbank.warmup.http-iterations`, `openbank.warmup.protected-path`. Each step logs `warm-up step <name>: <ms>`; a step failure is logged and does not stop the others.

## Safe XML and outbound TLS

Parse untrusted XML through `SecureXml` in `openbank-libs-domain`. Its DOM, SAX, StAX, schema and transformer factories disable external entities and DTD access; DOM and SAX reject a DOCTYPE entirely. Production parsers must not instantiate their own JAXP factories. `SecureXmlTest` and `XxeRejectionTest` cover external entity and DTD rejection.

For HTTPS, `SafeHttpClient` in `openbank-libs-runtime` uses JVM certificate chain validation and hostname verification. Test-only trust anchors still pass through the platform PKIX trust manager; callers cannot supply a custom trust manager or TLS context.

Internal clients that retain an injected JDK `HttpClient` (the LLM gateway, content guard, embeddings, flagd and OPA sidecar) use `BoundedBodyHandlers.ofString()` instead of unbounded `BodyHandlers.ofString()`. The default response cap is 4 MiB; exceeding it cancels the response subscription and fails the call with `IOException`. Use `SafeHttpClient` for external egress, where the response cap and destination-host policy are enforced together.
