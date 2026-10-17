// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    // Inline version (not the shared catalog) so enabling mutation testing stays path-scoped to
    // this service. 1.19.0 supports Gradle 9.
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
    // ADR-0281: service bearer token on the outbound net-settlement journal post to ledger-service.
    implementation(libs.quarkus.oidc.client.reactive.filter)
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
    // TraceContract: assert the observable distributed shape of a real operation (Test Intelligence
    // `trace` evidence) without exporting trace ids, attribute values or payloads.
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Per-job isolated test infra (issue #578): real Postgres + Redpanda + Valkey per test JVM.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    // Consumer-driven contract for this service's outbound money-path call (issue #8345).
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
                    // Ratchet floor at measured LINE coverage (65.2%) minus headroom, with the
                    // @Path / @RegisterForReflection excludes. No-regression baseline; raise as
                    // tests land. (#1130 follow-up — gate enabled below.)
                    minValue = 58
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.withType<Test> {
    // Gradle's default 512m test heap, the same limit account-service and eight other modules
    // raised for the same reason. clearing boots Quarkus for several @QuarkusTest classes alongside
    // Testcontainers (Postgres + Redpanda), and adding the Pact consumer runtime (#8345) tipped the
    // forked JVM over: OutOfMemoryError across the HTTP, Kafka and OTel threads, surfacing as a
    // read timeout in ClearingTraceContractIT and a 45-minute CI cancellation. Measured locally:
    // the full suite OOMs on this branch at 512m and is green on main without the Pact runtime.
    maxHeapSize = "2g"
}

// Mutation testing on the money-path domain + application layers (ADR-0063 / ADR-0030 D3, #13149).
// Weekly + manual via pitest.yml — never a per-PR gate. mutationThreshold is a RATCHET: measured
// score minus 2 points, raised as surviving mutants get killed, never lowered.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.clearing.domain.*", "com.openbank.clearing.application.*")
    // First run (#13149) with domain/application-only targetTests scored billing 0% and card-processing 4%:
    // their unit tests live in the package root, usecase/ and scheme/. Run every unit test and drop the
    // Quarkus/contract/e2e ones, which need infrastructure and would only add minutes per mutant.
    targetTests = setOf("com.openbank.clearing.*")
    excludedTestClasses = setOf(
        "com.openbank.clearing.integration.*", "com.openbank.clearing.it.*", "com.openbank.clearing.contract.*",
        "com.openbank.clearing.e2e.*", "com.openbank.clearing.testsupport.*",
    )
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.clearing.domain.*Kt", "com.openbank.clearing.application.*Kt")
}
