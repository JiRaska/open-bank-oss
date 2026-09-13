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
    // The oidc-client filter mints the shared openbank-services M2M token: ADR-0179's call
    // to account-service for the merge precondition guard, and the GDPR Art. 15 aggregation
    // hops to kyc-service / card-issuance-service (both endpoints are @RolesAllowed).
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.redis.client) // four-eyes ApprovalStore for party.merge (ADR-0155, ADR-0179)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Provider-side message Pact verification for the PARTY_CREATED event (ADR-0063 P1).
    testImplementation(libs.pact.provider)
    testImplementation(project(":openbank-libs-testing"))

    // CI infra pilot (P2): @QuarkusTest ITs get an isolated, per-JVM PostgreSQL +
    // Redpanda (Kafka API) via Testcontainers, instead of the shared compose stack
    // that flakes under full-fleet load. testcontainers core/junit come from the
    // version catalog; the postgresql + redpanda modules are pinned literally to the
    // SAME catalog version so this pilot stays scoped to one service (editing
    // openbank-libs/gradle/libs.versions.toml is a code-global change -> full-fleet).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:redpanda:1.20.4")
}

// Coverage ratchet (prod-readiness C2). Measured LINE coverage is ~65% (433/661);
// the floor is set just below at 60 to absorb measurement jitter, and is RAISE-ONLY
// — never lower it. REST adapters (thin, covered by *ApiIT) and reflection DTOs are
// excluded so the floor reflects the application/domain logic, mirroring ledger.
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
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.test {
    // The convention plugin pins DOCKER_HOST to the retired tcp://localhost:2375
    // (the OrbStack/EC2 endpoint). The ephemeral ARC dind pod (ADR-0053) exposes the
    // daemon on the unix socket instead, so for the Testcontainers-backed ITs we
    // inherit the runner pod's ambient DOCKER_HOST and fall back to the standard
    // unix socket. (Locally, the dev's ambient DOCKER_HOST / docker socket applies.)
    environment(
        "DOCKER_HOST",
        providers.environmentVariable("DOCKER_HOST").orElse("unix:///var/run/docker.sock").get(),
    )

    // Pact rootDir + Pact Broker property forwarding centralised into
    // build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
    // (ADR-0250 Phase 2, issue #4414) — this module previously forwarded the broker keys ITSELF
    // via `tasks.test { }` (not `tasks.withType<Test>`) and never set pact.rootDir at all; the
    // centralised version now covers both, applied uniformly across every Test task including
    // the new `providerPactTest`.
}
