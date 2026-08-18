// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
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
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    implementation(libs.quarkus.scheduler)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.quarkus.test.security)
    // In-memory reactive-messaging connector: SctInstBootSmokeIT swaps the Kafka outgoing
    // channel to InMemoryConnector so the boot smoke-test needs no broker (ADR-0104 D4 / #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contract for the transaction-service settlement call (ADR-0063, issue #468).
    testImplementation(libs.pact.consumer)
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
                    // Ratchet floor at measured LINE coverage (95.7%) minus headroom, with the
                    // @Path / @RegisterForReflection excludes. Ratchet-only: raised 52 -> 85 for
                    // the Q3 milestone (#321) after the adapter/outbox unit tests landed.
                    minValue = 85
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3). Weekly + manual via
// pitest.yml, advisory — never a per-PR gate. Per-service plugin pin on purpose (rules.yaml
// money_path_depth): keeping it out of the shared version catalog avoids a fleet-wide rebuild.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.sepainstant.domain.*")
    targetTests = setOf("com.openbank.sepainstant.domain.*", "com.openbank.sepainstant.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.sepainstant.domain.*Kt")
}

// Pact: write the generated consumer contract to pacts/ and forward broker config, matching
// transaction-service/fx-service/domestic-payment's tasks.withType<Test> block (ADR-0063 P1/P2).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.
