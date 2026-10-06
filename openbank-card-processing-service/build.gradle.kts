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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.scheduler)
    // Outbound M2M: the authorisation decision is card-issuance's (ADR-0194), the cleared posting
    // is transaction-service's (ADR-0103 rail CARD). Both hops authenticate as the service.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    // @TestSecurity: the REST surface is role-gated, so an IT driving real HTTP needs an identity.
    // Without it every request is anonymous and the test would only ever prove a 401.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // TestInfrastructureEvidence: records which container each IT actually started, so a suite that
    // silently skipped its infrastructure is distinguishable from one that ran against it.
    testImplementation(project(":openbank-libs-testing"))
    // Consumer-driven contract (ADR-0063): card-processing is a real consumer of card-issuance's
    // POST /api/v1/cards/{id}/authorizations. The consumer pact alone cannot catch a wrong PATH —
    // only the provider replay can — so the committed pact is replayed by card-issuance's
    // @PactFolder class, which runs on every PR (CLAUDE.md, #2327).
    testImplementation(libs.pact.consumer)
}

// CardAuthorization calculates the held and spent amounts; AuthorizationLifecycle decides
// whether a hold may clear, reverse, or expire. Other domain types are wire events and data
// carriers, whose generated accessors drown these decisions in irrelevant mutants (#8349).
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf(
        "com.openbank.cardprocessing.domain.model.CardAuthorization",
        "com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle",
    )
    targetTests = setOf("com.openbank.cardprocessing.domain.*", "com.openbank.cardprocessing.usecase.*")
    mutationThreshold = 70
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
}

tasks.test {
    // The HTTP/Postgres profiles restart Quarkus under Kover in one test fork. The default
    // Gradle test heap exhausts during class instrumentation before any test XML is emitted.
    maxHeapSize = "2g"
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020), set from the value measured when the service was
                    // introduced. Raise-only from here.
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
