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
    // Resource-server auth (issue #401): validate caller tokens for @Authenticated / @RolesAllowed.
    // The @Authorize interceptor, Roles and the Authorize annotation already arrive transitively via
    // :openbank-libs (which api-exports :openbank-libs-runtime + :openbank-libs-domain).
    implementation(libs.quarkus.oidc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation("com.networknt:json-schema-validator:1.5.9")

    // Persistence: reactive Panache + Postgres + Flyway, the fleet standard (ADR-0009/0105 P1).
    // openbank-libs is built on reactive Panache, so a service that depends on it must use reactive
    // too — a blocking ORM cannot index the libs reactive entities (JandexScavenger fails at build).
    // The Mutiny results are bridged to the suspend repository port via the coroutine adapter.
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.jdbc.postgresql) // Flyway runs migrations over JDBC
    implementation(libs.quarkus.flyway)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    implementation(project(":openbank-libs"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.quarkus.test.security)

    // Shared Testcontainers resource kit (issue #467) — pilot migration off the local
    // PostgresTestResource.kt copy.
    testImplementation(project(":openbank-libs-testing"))

    // Provider-side Pact verification of the consumer pacts in the repo-root pacts/ dir
    // (git-pact, ADR-0063 — no broker). Provider side only: product-catalog calls no other
    // service's API, so there is no pact-consumer dependency here.
    testImplementation(libs.pact.provider)
}

// Pact: provider verification reads pact files from the shared pacts/ dir (git-pact, ADR-0063).
// Set on the test JVM fork, not the Gradle daemon — System.setProperty would not propagate.
tasks.withType<Test> {
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")

    // Pact Broker verification (ADR-0092): forward the broker config CI passes with `-D`.
    // Without this the properties reach the Gradle daemon and stop there, so the @PactBroker
    // provider test is @EnabledIfSystemProperty(pactbroker.url)-skipped and pact-jvm logs
    // "Skipping publishing of verification results ... not 'true'" — even on a main push where
    // the workflow set PUBLISH_RESULTS=true. That is exactly how this module ended up with a
    // broker version carrying no branch and no verification result, leaving its consumers
    // permanently UNVERIFIED and undeployable (issue #3285).
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

// Coverage floor (ADR-0020, ratchet-only — sweep #466: this module previously had NO
// koverVerify gate at all). Floor = measured LINE coverage at introduction minus ~5 pt
// headroom; raise-only from here. Same excludes rationale as ledger/billing: thin REST
// adapters are covered by API ITs, reflection DTOs are data holders.
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
                    minValue = 90 // measured 95.4% (1315/1379) at introduction
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
