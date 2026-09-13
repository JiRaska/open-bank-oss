// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // CBOR codec for ISO 18013-5 mdoc / COSE_Sign1 verification (ADR-0094). pid-scoped direct
    // coordinate (not the shared catalog) so adding it does not trigger a fleet-wide rebuild.
    implementation("com.upokecenter:cbor:4.5.2")

    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Boot smoke-test (PidBootSmokeIT): per-job Testcontainers Postgres and the in-memory Kafka
    // connector, so a real Quarkus boot + Flyway runs in CI (issue #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Secret-free Testcontainers lifecycle evidence for the immutable Test Intelligence envelope.
    testImplementation(project(":openbank-libs-testing"))
    // @TestSecurity for the boot smoke-test's DB-touch assertion (calls /resolve with a role).
    testImplementation(libs.quarkus.test.security)
    // PidApiContractTest compares the served API against the committed openapi.yaml, which it PARSES
    // rather than greps. No version: managed by the enforcedPlatform(quarkus.bom) above
    // (testImplementation extends implementation), so it cannot drift from the runtime's Jackson.
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // Provider-side verification (git-pact, ADR-0063): PidPactFolderProviderVerificationTest
    // replays the pacts in pacts/ that name openbank-pid-service as provider — today
    // delegation-service's eligibility lookup (issue #2991). pid-service is a provider only, so
    // there is no pact-consumer dependency here.
    testImplementation(libs.pact.provider)
}

// Pact: forward broker config to the forked test JVM (issue #2991). pid-service publishes no
// consumer pacts, so `pact.rootDir` is only here for consistency with the fleet; the broker
// properties are the load-bearing half. Without them `pactbroker.url` never reaches the test JVM,
// PidPactBrokerProviderVerificationTest stays @EnabledIfSystemProperty-skipped even on main-push,
// and pid-service publishes no verification result — the exact `can-i-deploy` block that class
// exists to prevent. NOTE: must be set on the test JVM fork, not the Gradle daemon.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet-only floor (ADR-0029 rule #5): measured line coverage is ~57.6% as of
                    // this change (up from 0/unset); set a few points below so routine variance
                    // doesn't trip CI, but the floor never goes back down.
                    minValue = 55
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
