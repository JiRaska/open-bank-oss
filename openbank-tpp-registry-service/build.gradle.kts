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
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.redis.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":openbank-libs-testing"))
    // Boot smoke-test (TppRegistryBootSmokeIT): per-job Testcontainers Postgres + Valkey and the
    // in-memory Kafka connector, so a real Quarkus boot + Flyway runs in CI (issue #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Provider-side verification (ADR-0063 git-pact): TppRegistryPactProviderVerificationTest replays
    // the consumer pacts in pacts/ naming openbank-tpp-registry-service as provider (issue #2255).
    // quarkus-test-security supplies the @TestSecurity identity for the replayed requests, since
    // checkAuthorization is @RolesAllowed and @TestSecurity cannot annotate pact's @TestTemplate.
    testImplementation(libs.pact.provider)
    testImplementation(libs.quarkus.test.security)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 31.2% (113/362) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 26
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact Broker verification (ADR-0092): forward the broker config CI passes with `-D` into the
// (forked) test JVM. Without this the properties reach the Gradle daemon and stop there, so the
// @PactBroker provider test is @EnabledIfSystemProperty(pactbroker.url)-skipped and pact-jvm
// logs "Skipping publishing of verification results ... not 'true'" — even on a main push where
// the workflow set PUBLISH_RESULTS=true. That is exactly how this module ended up with a Pact
// Broker version carrying no branch and no verification result, leaving its consumers
// permanently UNVERIFIED and undeployable (issue #3285). 14 of 17 providers already had this
// block; the correlation with the three that did not was exact.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.
