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
