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
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
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
    // Outbound M2M bearer for the party-service client: POST /api/v1/parties and the mandate
    // endpoints are @RolesAllowed, so without the filter every call 401s (delegation-service
    // learned this against the deployed sandbox, never in a test).
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    // Durable case timers (ADR-0284; the ADR-0211 D2 shape lending uses): invitation reminders and
    // expiry, idle-case abandonment. The client/adapter half is build-time gated by
    // openbank.temporal.enabled; the worker half by openbank.kyb.worker.enabled.
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
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
                    // Domain (identifier checksums, representation-rule parser, case state
                    // machine) and use cases are unit-tested; the register adapters are covered
                    // by fixture-driven mapping tests. Ratchet moves UP only (ADR-0020).
                    minValue = 50
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
