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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    // Service-to-service auth: acquire a client-credentials token (openbank-services)
    // and attach it as a Bearer on the outbound MCP tool calls (ADR-0031 / ADR-0034).
    // Catalog alias maps to quarkus-rest-client-oidc-filter (the new REST client's OIDC filter).
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.smallrye.kafka)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Proposals store: plain Agroal JDBC (sync MCP path) + Flyway. NOT Hibernate ORM —
    // openbank-libs ships reactive Panache entities that the ORM entity scanner can't index
    // on this service's (non-reactive) classpath. See AgentProposal / ProposalService.
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(project(":openbank-libs"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // First integration test (ADR-0011 L2): isolated PostgreSQL per JVM (#578 pattern).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 0
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
