// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.pact.consumer)
}

// Pact: write generated consumer contracts to pacts/ and forward broker config.
tasks.withType<Test> {
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")
    listOf(
        "pactbroker.url",
        "pactbroker.auth.username",
        "pactbroker.auth.password",
        "pactbroker.enablePending",
        "pactbroker.providerBranch",
        "pact.verifier.publishResults",
        "pact.provider.version",
        "pact.provider.branch",
        "pact.provider.tag",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

kover {
    reports {
        filters {
            excludes {
                // Canonical money-path shape (ADR-0029 D3, mirrors openbank-ledger-service):
                // exclude only thin REST adapters (@Path) and reflection DTOs. Do NOT exclude
                // @ApplicationScoped — that is the application/use-case layer (the money logic)
                // and MUST count toward the floor.
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    // Ratchet floor at measured LINE coverage (92.4% over a small 105-line base)
                    // minus generous per-line headroom, with the @Path / @RegisterForReflection
                    // excludes. Early scaffold — no-regression baseline; raise as the service
                    // grows. (#1130 follow-up — gate enabled below.)
                    minValue = 85
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
