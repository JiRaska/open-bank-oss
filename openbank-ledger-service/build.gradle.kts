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
    // Redis-backed ApprovalStore for the ADR-0155 four-eyes mechanism (ledger has no other
    // Idempotency-Key/Redis surface today — this is the first Redis dependency in this service).
    implementation(libs.quarkus.redis.client)
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
    // Property-based testing of double-entry invariants (ADR-0011 L1 — Kotest property).
    // Declared inline (not in the shared version catalog) so this test-only dependency does not
    // trigger a fleet-wide rebuild; only this service recompiles (path-scoped CI).
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Provider-side Pact verification (ADR-0063)
    testImplementation(libs.pact.provider)
    // Consumer-driven contract for the fx-service CNB fixing lookup the daily revaluation makes
    // (#3921): ledger is the CONSUMER of GET /api/v1/fx/rates/{base}/{quote}?source=CNB&asOf=,
    // so this module also generates a pact. Only the provider replay can catch a wrong request
    // shape, and `?asOf=` is exactly that (#2269/#2290).
    testImplementation(libs.pact.consumer)
    // CI infra sweep (#578): isolated PostgreSQL (+ Redpanda for non-in-memory ITs) per JVM.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)

    // Shared authz conformance kit (issue #467) — LedgerAuthzConformanceTest pilot migration.
    testImplementation(project(":openbank-libs-testing"))
}

kover {
    reports {
        filters {
            excludes {
                // REST adapters are thin and covered by *ApiIT integration tests; reflection
                // DTOs are trivial data holders. We do NOT exclude @ApplicationScoped: that is
                // the application/use-case layer (LedgerService etc.) — the money logic that
                // MUST count toward the floor. Excluding it measured coverage over domain
                // models/DTOs only and let the floor pass while the orchestration went uncounted.
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 65
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: provider verification reads pact files from the shared pacts/ dir (git-pact, ADR-0063).
System.setProperty("pact.rootDir", "${rootProject.projectDir}/pacts")

// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

tasks.withType<Test> {
    // Same class of OOM as account-service's override above it in git history (issue #4414):
    // Gradle's default test-JVM heap is 512m, unchanged fleet-wide. ledger-service carries 14
    // @QuarkusTest classes plus a Kotest property test (LedgerServiceIdempotencyPropertyTest)
    // that allocates many double-entry journal fixtures per run. CI repeatedly died with
    // `java.lang.OutOfMemoryError` inside the test JVM — repeatable on rerun, not a one-off
    // shared-runner spike. Per-module rather than a fleet default, same reasoning as
    // account-service/lending-service/product-catalog: nothing measures test heap anywhere, so
    // bump the module that is actually short rather than every module that might be.
    maxHeapSize = "2g"
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3). Weekly + manual via
// pitest.yml, advisory — never a per-PR gate. info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.ledger.domain.*")
    targetTests = setOf("com.openbank.ledger.domain.*", "com.openbank.ledger.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.ledger.domain.*Kt")
}
