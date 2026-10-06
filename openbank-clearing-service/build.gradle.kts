// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
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

// The reactive facade decides idempotent payment admission, per-currency batches and
// reconciliation; the direct unit suite probes those decisions without external brokers (#8349).
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.clearing.application.usecase.ClearingService")
    targetTests = setOf("com.openbank.clearing.application.usecase.ClearingServiceTest")
    // Kotlin inserts non-null checks in this reactive facade; removing them generated 27
    // surviving compiler-shape mutants unrelated to clearing decisions in the first baseline.
    avoidCallsTo = setOf("kotlin.jvm.internal.Intrinsics")
    mutationThreshold = 70
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
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
