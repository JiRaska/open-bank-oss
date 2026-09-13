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
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.mailer)
    implementation("io.quarkus:quarkus-scheduler")

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    // TraceContract: assert the observable distributed shape of a real operation (Test Intelligence
    // `trace` evidence) without exporting trace ids, attribute values or payloads.
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Provider verification (ADR-0063 P2 Batch B): consent-service calls GET /api/v1/sca/challenges.
    testImplementation(libs.pact.provider)
    // Pact provider test uses Testcontainers for a real DB and Redis boot.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // sca-events-out Kafka emitter is switched to in-memory connector in tests.
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
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
                    minValue = 82
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: forward broker config so the provider verification test can fetch and publish results.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Mutation testing on the security-critical domain (ADR-0063 / ADR-0030 D3, issue #8349). Weekly +
// manual via pitest.yml, advisory — never a per-PR gate.
//
// Why this service is in scope while clearing/swift/lending are deliberately not: the exclusion in
// pitest.yml is about THIN domains (one model file, no branching worth mutating), not about
// money-path membership. Strong customer authentication (PSD2 RTS Art. 4/5): the challenge lifecycle decides whether an
// authentication succeeds, and the attempt counter is what makes brute force bounded. A surviving
// mutant there is an authentication check that never fires.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.sca.domain.*")
    targetTests = setOf("com.openbank.sca.domain.*", "com.openbank.sca.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score and warns below 70%; the Gradle task itself
    // must not fail the run, so the threshold is 0 until the score is measured and sustained.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.sca.domain.*Kt")
}
