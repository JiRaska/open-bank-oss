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
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    // Shared TemporalConfig + TemporalClientProducer (ADR-0209 D1, #2572).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.65.1")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Property-based testing of FX conversion margin invariants (ADR-0011, issue #469).
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Provider verification (ADR-0063 P2 Batch B): transaction-service calls GET /api/v1/fx/rates.
    testImplementation(libs.pact.provider)
    // Consumer-driven contract for the aml-service case store (issue #2255, C3): fx is the consumer
    // of POST /api/v1/aml/cases, so this module also generates a pact.
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
                // Shared library classes are on this module's classpath, so Kover counts them —
                // but fx's test task never runs libs' tests, so any code fx does not itself call
                // is measured as uncovered. That makes this floor a function of how large
                // openbank-libs-* is rather than of how well fx is tested: #5719 added 13 lines to
                // libs-runtime's EventRetry (53 -> 66), which fx never references, and fx dropped
                // to 60.5% against a floor of 65 on a PR that touches no fx file. The floor's own
                // comment below cites a measurement of fx's code; this keeps the number meaning
                // that. Fleet-wide fix tracked in issue #6384.
                classes("com.openbank.libs.*")
            }
        }
        verify {
            rule {
                bound {
                    // Ratcheted from 40 after real line coverage measured ~69% (test/fx-service-coverage):
                    // CnbResource, FxWorkflowImpl, the sanctions/AML/fraud/ČNB REST-client adapters, the
                    // outbox dispatcher, and the daily ingestion scheduler gained real unit tests. Kept a
                    // few points below the measured figure for headroom, never below the prior floor.
                    minValue = 65
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3; fleet rollout #1266). Weekly +
// manual via pitest.yml, advisory — never a per-PR gate. info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.fx.domain.*")
    targetTests = setOf("com.openbank.fx.domain.*", "com.openbank.fx.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.fx.domain.*Kt")
}

// Pact: forward broker config so the provider verification test can fetch and publish results.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.
