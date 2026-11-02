// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.mockk)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contract test: the account.created message contract that triggers
    // referral qualification (ADR-0310 D1).
    testImplementation(libs.pact.consumer)
    // Provider side: referral.qualified.v1 is pacted against — loyalty-service earns Lístky from
    // the Qualified event, replayed by ReferralEventPactFolderProviderVerificationTest.
    testImplementation(libs.pact.provider)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

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
