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
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // ADR-0334 S8: FundAdministrationPort -> pension-fund-service over REST, with pension-service's
    // OWN client-credentials token (Keycloak client openbank-pension, ROLE_API only).
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    // ADR-0334 S3: monthly incentive claim run + subscription sweep.
    implementation(libs.quarkus.scheduler)
    // #12378: domestic-payment status events -> payout settlement (DLQ-wired consumer).
    // #12379: participant notices onto notification-service's request topic.
    implementation(libs.quarkus.smallrye.kafka)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Onboarding/transfer (S2) and exit (S5) workflows: shared TemporalConfig + client producer (ADR-0209 D1).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":openbank-libs-testing"))
    // Consumer pacts for the sca/party/account identity checks (#12377), written to pacts/.
    // #12379: consumer pact for the document-service render call (ADR-0063 P2).
    testImplementation(libs.pact.consumer)
    // #12425: @PactFolder replay of tax-reporting-service's consumer pact (reporting read model).
    testImplementation(libs.pact.provider)
    // RepinCzDpsV1MigrationTest drives Flyway directly (target V11, then V12) against a real Postgres.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
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
                    // The aggregate, pack evaluation and the use case are unit-tested; REST and
                    // persistence are covered by the integration test. Ratchet moves UP only
                    // (ADR-0020) — set from the first green run, never lowered.
                    minValue = 50
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // ADR-0334 S8: the integrated module boots Quarkus once per distinct test profile (the journey
    // E2E and the cron IT each bring one), and every boot holds an in-process
    // Temporal test server; at the default heap the executor died with OutOfMemoryError (exit 134)
    // and every class after it simply never reported. Same per-module override as account-service.
    maxHeapSize = "2g"
}
