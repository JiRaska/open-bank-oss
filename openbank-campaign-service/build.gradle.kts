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

    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Test-only (#5705): CampaignMetricsAdapterTest asserts the alert expressions in
    // gitops/components/campaign/prometheus-rules.yaml against a real Prometheus scrape, so the
    // dot->underscore mapping, the counter's `_total` suffix and the tag ordering come from
    // Micrometer rather than from this test's idea of them. Runtime already ships the registry via
    // quarkus-micrometer-registry-prometheus, but as the `-simpleclient` variant (package
    // io.micrometer.prometheus); only the compile classpath needs the io.micrometer.prometheusmetrics
    // one. Same version, same rationale and same CVE pin as openbank-domestic-payment's copy:
    // 1.14.5 -> 1.17.0 for GHSA-g3pr-3p32-fp23 / CVE-2026-40984 (HIGH DoS; the 1.14.x line has no
    // fix), and this literal — not quarkus-bom's constraint — is what dependency-review scans.
    testImplementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
    testImplementation(libs.rest.assured.kotlin)
    // Consumer-driven contract against consent-service (ADR-0063). campaign-service reads the
    // suppression list and the consent check on every outbound touch, and both were unverified:
    // `GET /api/v1/suppressions/party/{partyId}` answered 500 on EVERY call from the day it
    // shipped (#5711) and nothing here noticed, because the only tests of this client mock it.
    testImplementation(libs.pact.consumer)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // CampaignRestContractIT drives the real HTTP surface against real Postgres/Redis (#3133).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":openbank-libs-testing"))
}

// Coverage is ratchet-only (never lower). Without this block koverVerify has no rule to check,
// so the module's coverage was measured by nobody — and the production-readiness collector
// scores C2 off the presence of a ratchet, not off a number, which is the honest signal: a
// floor that cannot go down is a guarantee, a percentage in a report is not.
// Measured on 2026-08-19: 72.9% LINE. The bound is the fleet-standard 60, i.e. below
// today's level on purpose — a floor is a floor, not a target, and one set at the current
// reading turns every unrelated refactor into a red build.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
