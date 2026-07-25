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

    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Consumer-driven contract test for the ledger trial-balance call (ADR-0063, git-pact).
    // Consumer side only — finrep is not a Pact provider for anyone, so no libs.pact.provider.
    testImplementation(libs.pact.consumer)
}

// Pact: write generated pact files to the shared pacts/ dir at the repo root (git-pact, ADR-0063).
// The consumer test regenerates the file on every run; developers commit the result, and
// openbank-ledger-service's LedgerPactProviderVerificationTest (@PactFolder("../pacts")) replays it.
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not propagate).
tasks.withType<Test> {
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
