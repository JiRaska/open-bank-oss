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
    implementation(libs.quarkus.scheduler)
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Shared TemporalConfig + TemporalClientProducer (ADR-0209 D1, #2572).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    // In-process Temporal test server for the sole-orchestrator dispatch/workflow tests (ADR-0120 Phase 6, #1917).
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    // @TestSecurity for the ADR-0034 Phase 5 advisory-mode authz regression test.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Test-only (#4221): FraudScoringMetricsTest asserts the alert expressions in
    // gitops/components/payments/prometheus-rules.yaml against a real Prometheus scrape, so the
    // dot->underscore mapping, the counter's `_total` suffix and the tag ordering come from
    // Micrometer rather than from this test's idea of them. Runtime already ships the registry via
    // quarkus-micrometer-registry-prometheus; only the compile classpath needs it. Same version and
    // same rationale as openbank-libs-runtime's WorkflowLivenessMetricNamingTest.
    // 1.14.5 -> 1.17.0: GHSA-g3pr-3p32-fp23 / CVE-2026-40984 (HIGH DoS; the 1.14.x line has no
    // fix). This literal, not quarkus-bom's own constraint, is what dependency-review scans on
    // the test classpath — same root cause as openbank-libs-runtime's pin. Issue #5482.
    testImplementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // ADR-0063 P2: consumer-driven contract tests (Pact).
    testImplementation(libs.pact.consumer)
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
                    // Measured LINE coverage 79% after the unit-test sweep; remaining gap is the
                    // DB-bound *RepositoryImpl layer (needs Testcontainers integration tests).
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write generated consumer contracts to pacts/ and forward broker config for provider
// verification (ADR-0063 P2). pactbroker.* props are injected by CI with -D.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3). Weekly + manual via
// pitest.yml, advisory — never a per-PR gate. Per-service plugin pin on purpose (rules.yaml
// money_path_depth): keeping it out of the shared version catalog avoids a fleet-wide rebuild.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.domestic.domain.*")
    targetTests = setOf("com.openbank.domestic.domain.*", "com.openbank.domestic.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.domestic.domain.*Kt")
}
