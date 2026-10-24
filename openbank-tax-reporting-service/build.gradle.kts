// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
}

// Compile the corporate-facts test double used by this module's consumer Pact tests.
kotlin.sourceSets.named("consumerPactTest") {
    kotlin.include("com/openbank/tax/contract/NoCorporateFacts.kt")
}

dependencies {
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.opentelemetry)
    // #12425: REST adapters to the pension reporting read models, on this service's OWN M2M client.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.mockk)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(project(":openbank-libs-testing"))
    // #12425: consumer pacts for the pension reporting read models, written to pacts/.
    testImplementation(libs.pact.consumer)
}

// Independent Quarkus boot profiles retain more than the default 512 MiB test heap.
// The complete real-DB/Kafka suite measured 680 MiB after GC with a 1 GiB cap (#12250).
tasks.named<Test>("test") {
    maxHeapSize = "1g"
}

// Coverage floor (ADR-0020, ratchet-only). This module had no verify{} rule, so koverVerify
// passed trivially — "ungated" read exactly like "gated". 80.94% LINE (1452/1794) measured
// locally on 2026-10-17 over this service's own sources (the fleet filter excludes
// com.openbank.libs.*), minus 2. Raise-only from here; gate `coverage-floor-ratchet` holds the
// direction.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 78
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
