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
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // First-increment PEP screening (ADR-0116): outbound call to openbank-sanctions-service's
    // POST /api/v1/sanctions/screen, mirroring the same client stack already used by
    // openbank-domestic-payment / openbank-sepa-payment / openbank-fx-service / openbank-account-service.
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.cache)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // @TestSecurity for KycOutboxWriteIT (#4007): the outbox write can only be proved through a
    // real HTTP request (a reactive Panache repo called from a bare @QuarkusTest thread has no
    // Vert.x context), and every KYC endpoint is @RolesAllowed.
    testImplementation(libs.quarkus.test.security)
    // Consumer-driven contract for the party-events Kafka messages (ADR-0063, issue #468).
    testImplementation(libs.pact.consumer)
    // Provider-side verification of kyc-case events onboarding-service consumes (ADR-0063,
    // issue #468). Git-pact (@PactFolder) — no broker needed.
    testImplementation(libs.pact.provider)
    // #1201: isolated PostgreSQL per test JVM via Testcontainers (kyc-service had no IT infra
    // before this — matches every other outbox-bearing service's PostgresTestResource pattern).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // #8432: KycOutcomeNotificationWireIT swaps `notification-requests-out` for an in-memory sink
    // so the test can read what is ACTUALLY put on the wire. A mocked CustomerNotificationPort
    // proves only that KycService called a port; it cannot see an unresolvable @Channel emitter,
    // nor a payload notification-service would reject. Same dependency account-service and
    // campaign-service already carry for this channel.
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Shared, secret-free lifecycle evidence collected into the Test Intelligence envelope in CI.
    testImplementation(project(":openbank-libs-testing"))
}

// Pact: write the generated consumer contract to pacts/ and forward broker config, matching
// account-service/ledger-service's tasks.withType<Test> block (ADR-0063 P1/P2).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 45.4% (227/500) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 40
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
