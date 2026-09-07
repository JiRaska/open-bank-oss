// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    // Inline version (not the shared catalog) so enabling mutation testing stays path-scoped to
    // this service and does not trigger a fleet-wide rebuild. 1.19.0 supports Gradle 9.
    id("info.solidsoft.pitest") version "1.19.0"
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
    // #889: initiate the real SEPA transfer for a due standing order via the sepa-payment REST API,
    // with an openbank-services M2M token minted by the oidc-client filter.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    implementation(libs.quarkus.scheduler)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // @TestSecurity: StandingOrderOutboxAtomicityIT (#8353) drives the @RolesAllowed lifecycle
    // endpoints over real HTTP — the only way to exercise a reactive Panache write, since a bare
    // @QuarkusTest thread carries no Vert.x context. The existing ITs only hit unsecured routes.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Consumer-driven contract against transaction-service's POST /api/v1/transactions (#8345).
    // pact.rootDir and the pactbroker.* forwarding are centralised in the
    // `openbank.quarkus-service` convention plugin (ADR-0250 Phase 2, #4414), so this dependency
    // is the whole of the per-module wiring.
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 39.3% (154/392) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 34
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing (ADR-0063 / ADR-0030 D3).
// StandingOrder carries the recurrence/failure arithmetic that decides whether the next
// payment fires: calculateNextDate per Frequency, MAX_CONSECUTIVE_FAILURES, and the
// ONCE/endDate completion boundaries. 9 branch sites across 2 domain files.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.standingorder.domain.*")
    targetTests = setOf("com.openbank.standingorder.domain.*", "com.openbank.standingorder.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.standingorder.domain.*Kt")
}
