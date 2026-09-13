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
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    // TraceContract: assert the observable distributed shape of a real operation (Test Intelligence
    // `trace` evidence) without exporting trace ids, attribute values or payloads.
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contract test against sca-service getChallenge (ADR-0063 P2 Batch B).
    testImplementation(libs.pact.consumer)
    // Provider-side verification (ADR-0063 git-pact): ConsentPactProviderVerificationTest replays the
    // consumer pacts in pacts/ that name openbank-consent-service as provider (issue #2255).
    testImplementation(libs.pact.provider)
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
                    // Measured LINE coverage is 73% (unit tests). Floor set below that with headroom;
                    // the remaining gap is the DB-bound repository layer (needs integration tests).
                    minValue = 69
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write generated consumer contracts to pacts/ and forward broker config for verification.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Mutation testing on the security-critical domain (ADR-0063 / ADR-0030 D3, issue #8349). Weekly +
// manual via pitest.yml, advisory — never a per-PR gate.
//
// Why this service is in scope while clearing/swift/lending are deliberately not: the exclusion in
// pitest.yml is about THIN domains (one model file, no branching worth mutating), not about
// money-path membership. PSD2/GDPR consent: the lifecycle decides whether a third party may read an account at all, and the
// suppression rules decide whether a customer contact is lawful. A surviving mutant there is a
// revocation that does not revoke.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.consent.domain.*")
    targetTests = setOf("com.openbank.consent.domain.*", "com.openbank.consent.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score and warns below 70%; the Gradle task itself
    // must not fail the run, so the threshold is 0 until the score is measured and sustained.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.consent.domain.*Kt")
}
