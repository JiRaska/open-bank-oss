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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    // SettlementStrandedGauge's 30s refresh tick (issue #5705).
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Shared TemporalConfig + TemporalClientProducer (ADR-0209 D1, #2572).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Test-only (#5705): SettlementStrandedGaugeTest, SettlementActivitiesImplTest and
    // SettlementMetricsAdapterTest assert the alert expressions in
    // gitops/components/payments/prometheus-rules.yaml against a real Prometheus scrape, so the
    // dot->underscore mapping, the counter's `_total` suffix and the tag ordering come from
    // Micrometer rather than from these tests' idea of them. Runtime already ships the registry via
    // quarkus-micrometer-registry-prometheus, but as the `-simpleclient` variant (package
    // io.micrometer.prometheus); only the compile classpath needs the io.micrometer.prometheusmetrics
    // one. Same version, same rationale and same CVE pin as openbank-campaign-service's and
    // openbank-domestic-payment's copies: 1.14.5 -> 1.17.0 for GHSA-g3pr-3p32-fp23 /
    // CVE-2026-40984 (HIGH DoS; the 1.14.x line has no fix), and this literal — not quarkus-bom's
    // constraint — is what dependency-review scans.
    testImplementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Consumer-driven contract for the ledger-service postJournal call (ADR-0063, issue #468).
    testImplementation(libs.pact.consumer)
    // Real-HTTP stand-in for balance-service so the reversal adapters' REST calls actually leave
    // the process in SettlementReversalIT (#6037) — a mocked port cannot prove a money movement
    // was addressed to anyone. Same use as sepa-payment's simulator resources.
    testImplementation(libs.wiremock.standalone)
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
                    // Ratchet: measured 85.49% line coverage after adding workflow-saga,
                    // outbound-adapter and repository tests (was 31.66%, floor was a stale 18).
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write the generated consumer contract to pacts/ and forward broker config, matching
// transaction-service/fx-service/lending-service's tasks.withType<Test> block (ADR-0063 P1/P2).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.
