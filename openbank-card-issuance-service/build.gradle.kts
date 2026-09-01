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
    // product-catalog entitlement lookup (#4): RestClient + the openbank-services M2M bearer,
    // same shape as account-service's / document-service's ProductCatalogClient.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
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
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // Consumer-driven contract tests: the product-catalog card-entitlement lookup (ADR-0063) and
    // the `openbank.delegation.events` message contract this service projects (ADR-0232 D3).
    testImplementation(libs.pact.consumer)
    // Provider side: as of issue #2991 card-issuance IS pacted against — delegation-service reads
    // GET /api/v1/cards/{id} to verify the grantor holds the card before offering a grant.
    testImplementation(libs.pact.provider)

    // CI infra sweep (#578): isolated PostgreSQL + Valkey(Redis) per test JVM
    // via Testcontainers. Kafka is already in-memory in the IT (no broker).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// Pact: write generated pact files to the shared pacts/ dir at the repo root (git-pact, ADR-0063).
// The consumer test regenerates the file on every run; developers commit the result.
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not propagate).
//
// The broker properties are forwarded for the same reason (issue #2991): without them
// `pactbroker.url` never reaches the forked test JVM, CardIssuancePactBrokerProviderVerificationTest
// stays @EnabledIfSystemProperty-skipped on EVERY lane including main-push, and card-issuance keeps
// publishing no verification result — which is the `can-i-deploy` block the class exists to prevent.
// A twin that cannot see the broker looks identical to not having one.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 48.7% (190/390) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 43
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
