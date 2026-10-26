# Quarkus 3.40 LTS upgrade

Tracking: #13888.

The platform BOM and Gradle plugin use **3.40.1.1**. Shared library Quarkus API
dependencies read the same catalog version so their standalone compilation and tests
exercise the framework used by services. Their existing API/compile-only scopes are
preserved. No API contract, database schema or event format changes are intended.

## Dependency compatibility

- Keep the Netty family on the platform's **4.1.139.Final** security patch on both
  the service and build-plugin classpaths. Older forced versions override BOM fixes;
  a partial 4.2 override mixes incompatible minor lines.
- Retain the existing Jackson 2.22.3 and other security overrides until independently
  validated. A platform bump is not permission to remove newer security fixes.
- Advance older PostgreSQL, Bouncy Castle, HttpClient and Commons IO overrides to
  the new BOM versions too; explicit forces must not downgrade its patched libraries.
- Shared-library Micrometer APIs and test registries use **1.17.1**, as managed by
  the new platform. Preserve the existing Kotlin and Temporal versions in this stage.
- Add checksums for newly resolved artifacts without deleting old verification entries.

Upstream migration notes for [3.39](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.39)
rename the new Quarkus Data API; the existing Hibernate Reactive Panache API used here
is distinct. [3.40](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.40)
continues the 3.39 stream. The [3.40.1.1 release](https://quarkus.io/blog/quarkus-3-40-1-1-released/)
includes security fixes. The [3.40 LTS announcement](https://quarkus.io/blog/quarkus-3-40-released/)
declares twelve months of support from 2026-09-30.

## Verification and rollout

Run shared-library tests and representative full service builds, including real
HTTP/database tests and Quarkus augmentation. Check test failures **and skips**;
successful compilation alone does not prove Hibernate, CDI or metrics compatibility.
Run lint independently if a test failure prevented it from running. Inspect resolved
Netty, Micrometer, Jackson, Hibernate and OpenTelemetry versions with Gradle
`dependencyInsight`. Complete repository gates and required fleet CI before promotion.

Use the normal reviewed deployment process. Compare readiness, HTTP errors and
latency, metrics export, Kafka consumer progress and outbox backlog against the
previous image under the same workload. This document specifies checks; it is not
evidence that deployment or runtime verification has completed.

## Rollback

Retain the previous immutable service images and promote them through the normal
reviewed GitOps process if the new runtime fails its checks. Revert the upgrade commit
to restore the previous build inputs. No database rollback or event rewrite is needed
for this dependency-only stage. Do not apply this rollback recipe to later Temporal
versioning changes: those must retain workers required by in-flight workflow histories.
