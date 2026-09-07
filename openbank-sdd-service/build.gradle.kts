// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    // Inline version (not the shared catalog) so enabling mutation testing stays path-scoped to
    // this service and does not trigger a fleet-wide rebuild. 1.19.0 supports Gradle 9.
    id("info.solidsoft.pitest") version "1.19.0"
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
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    // #1000: book the debtor-side debit for an authorised SDD collection via transaction-service,
    // with an openbank-services M2M token minted by the oidc-client filter.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)

    // CI infra sweep (#578): isolated PostgreSQL per test JVM via Testcontainers.
    // Kafka is already in-memory in the IT, so no broker container is needed.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)

    // Consumer-driven contract against transaction-service's POST /api/v1/transactions (#8345).
    // pact.rootDir, `pact.writer.overwrite` and the pactbroker.* forwarding are centralised in the
    // `openbank.quarkus-service` convention plugin (ADR-0250 Phase 2, #4414), so this dependency is
    // the whole of the per-module wiring.
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 43.8% (219/500) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 38
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3; money-path matrix
// extension, issue #3675). Weekly + manual via pitest.yml, advisory — never a per-PR gate.
// info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.sdd.domain.*")
    targetTests = setOf("com.openbank.sdd.domain.*", "com.openbank.sdd.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.sdd.domain.*Kt")
}
