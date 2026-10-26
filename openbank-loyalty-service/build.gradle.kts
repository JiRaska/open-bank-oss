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
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// Gradle's default test-JVM heap is 512m. Loyalty now boots THREE Quarkus profiles in one forked
// JVM alongside Testcontainers (the default IT profile plus the referral-consumer real-broker E2E
// profile), and the run died with `OutOfMemoryError: Java heap space` inside the fork — surfacing
// as "Test process encountered an unexpected problem" with the last-started IT named as the
// failure, which is the misleading part. Same per-module override as account/lending/
// product-catalog (ADR-0250 Phase 2 era); deliberately not a fleet default for the reasons
// recorded in account-service's build.gradle.kts.
tasks.withType<Test> {
    maxHeapSize = "2g"
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
