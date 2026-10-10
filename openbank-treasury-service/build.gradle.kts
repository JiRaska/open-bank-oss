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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    // ledger-service journal API (ADR-0315 D5), bearer from the named oidc-client `m2m` (#10486).
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    // semt.002 statement-of-holdings reader (ADR-0337 amendment): the fleet's ISO 20022 library.
    implementation(project(":openbank-libs-iso20022"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contract test for the nostro ledger reads (ADR-0315 D5, #10896).
    testImplementation(libs.pact.consumer)
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
                    // Money-path (rules.yaml: money_path_services): the deal aggregate, posting
                    // table and use case are unit-tested; REST, persistence and the ledger adapter
                    // by the integration test. Ratchet moves UP only (ADR-0020).
                    minValue = 70
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3, issue #8349). Weekly + manual
// via pitest.yml, advisory — never a per-PR gate.
//
// Why this service: it is money-path (ADR-0315) and its domain is the densest in the fleet by the
// pitest.yml measure — 142 branch sites across 6 files. Deal's state machine, the four-eyes and
// limit checks, day-count interest and the posting rules decide what reaches the ledger. A
// surviving mutant there is a booking the checks were supposed to stop.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.treasury.domain.*")
    targetTests = setOf("com.openbank.treasury.domain.*", "com.openbank.treasury.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score and warns below 70%; the Gradle task itself
    // must not fail the run, so the threshold is 0 until the score is measured and sustained.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.treasury.domain.*Kt")
}

tasks.withType<Test> {
    // Gradle's default test-JVM heap is 512m. Treasury now boots Quarkus four times in one forked
    // JVM (the shared profile plus the quotes, confirmation-not-required and nostro break-sweep
    // @TestProfiles), alongside Testcontainers and Kover instrumentation. Measured 2026-10-01 under
    // `build --rerun-tasks`: the heap sat at 522 of 524 MB, Kover's class transformer could no longer
    // allocate ("can't create byte array") and the run wedged without failing. Same per-module
    // override, and the same reasoning, as account-service.
    maxHeapSize = "2g"
}
