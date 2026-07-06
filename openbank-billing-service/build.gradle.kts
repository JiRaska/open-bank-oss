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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // ADR-0143 phase 2c (read path): reactive REST clients to product-catalog (fees) and
    // account/balance (FeeContext), with OIDC client-credentials propagation on the PII reads.
    // Still no datastore / outbox / posting — assessment is read-only here.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.oidc.client.reactive.filter)

    // The shared fee-waiver engine (ADR-0138 phase 1b).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
}

// Coverage floor (ADR-0020, ratchet-only — issue #321: billing was the only money-path
// service with NO koverVerify gate at all). Measured 98.4% line coverage at introduction
// (121/123); floor set at 90 so a 123-line service isn't brittle to a single new branch,
// raise-only from here.
kover {
    reports {
        filters {
            excludes {
                // Same rationale as the ledger config: thin REST adapters are covered by
                // ApiIT tests; reflection DTOs are data holders. @ApplicationScoped
                // (use-case layer) DOES count toward the floor.
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 90
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.named("koverVerify") {
    enabled = true
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
