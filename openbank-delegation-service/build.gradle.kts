// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    // Inline version (not the shared catalog) so enabling mutation testing stays path-scoped to
    // this service and does not trigger a fleet-wide rebuild. 1.19.0 supports Gradle 9.
    id("info.solidsoft.pitest") version "1.19.0"
}

tasks.test {
    // The full module suite boots multiple Quarkus/Testcontainers test profiles and replays the
    // delegation message Pacts. Gradle's default test heap can OOM before the replay finishes,
    // making the interactions misleadingly appear as missing. Keep this service aligned with the
    // account/lending money-path suites, which carry the same measured 2 GiB override.
    maxHeapSize = "2g"
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // Outbound M2M bearer for every REST client this service owns. Without it the calls go out
    // UNAUTHENTICATED and every one of them 401s: sca-service, pid-service, account-service and
    // card-issuance-service and document-service all sit behind @RolesAllowed. Found only against the deployed sandbox
    // — every unit test mocks the client interface, so the missing Authorization header is
    // invisible to the whole suite. Same pattern as party-service / document-service / sdd-service.
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Secret-free Testcontainers lifecycle evidence for the immutable Test Intelligence envelope.
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contracts for the services delegation-service calls before it will
    // mint a grant (sca, pid, account, card-issuance) — issue #2991.
    testImplementation(libs.pact.consumer)
    // Provider side: delegation-service is the provider of the `openbank.delegation.events`
    // message contract that account-service and card-issuance build enforcement projections from.
    testImplementation(libs.pact.provider)
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    // First slice: domain + use-case unit tests; the DB-bound repository layer
                    // is covered by Testcontainers ITs that kover does not measure. Ratchet
                    // may only move UP from here (ADR-0020).
                    minValue = 50
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Mutation testing (ADR-0063 / ADR-0030 D3).
// ADR-0249 D3 spend-ceiling arithmetic (SpendCeilings.evaluate / headroom clamp,
// SpendReservation lifecycle) is real money-movement math on BigDecimal — the criterion in
// rules.yaml: coverage.money_path_depth. 60 branch sites across 7 domain files.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.delegation.domain.*")
    targetTests = setOf("com.openbank.delegation.domain.*", "com.openbank.delegation.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.delegation.domain.*Kt")
}
