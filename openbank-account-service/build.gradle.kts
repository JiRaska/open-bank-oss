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
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.redis.client)
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

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Provider-side message Pact verification for the AccountCreated event (ADR-0063 P1).
    testImplementation(libs.pact.provider)
    // Consumer-driven message contract for the PARTY_CREATED event account consumes (ADR-0063 P1).
    testImplementation(libs.pact.consumer)
    // CI infra sweep (#578): isolated PostgreSQL + Redpanda + Valkey per test JVM.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
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
                    // Coverage floor (ADR-0020, ratchet-only — issue #321 Q3 milestone).
                    // Measured 69.2% LINE coverage after adding unit tests for
                    // AccountService lifecycle/pockets, AuthorizationService, and
                    // AccountAuthorization (@Path / @RegisterForReflection excluded from
                    // the measurement, per the kover filter above). Floor raised 47 -> 65,
                    // a few points below measured so the gate isn't brittle to a single new
                    // branch in untested code; raise-only from here.
                    minValue = 65
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write generated consumer contracts to the shared pacts/ dir at the repo root (git-pact).
// account-service is BOTH a consumer (PARTY_CREATED) and a provider (AccountCreated).
tasks.withType<Test> {
    // Gradle's default test-JVM heap is 512m, which no module in this fleet overrides. That was
    // survivable while account-service booted Quarkus once; it now has two @QuarkusTestProfile
    // classes (the outbox-claim IT and the savings-proposal expiry-scheduler IT), and each profile
    // forces its OWN Quarkus boot in the same forked JVM alongside Testcontainers. CI died with
    // `java.lang.OutOfMemoryError` inside QuarkusTestExtension, surfacing as a scheduler assertion
    // failure — the misleading part, because the test it names is a negative control that asserts
    // nothing happened.
    //
    // Deliberately per-module rather than a fleet default: nothing measures test heap anywhere, so
    // a global bump would be an unmeasured ratchet applied to 50 modules to fix one. If a second
    // module starts OOMing, that is the signal to raise it in build-logic and to count the Quarkus
    // boots per module rather than keep paying for them.
    maxHeapSize = "2g"

    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")

    // Pact Broker verification (ADR-0092): forward the broker config CI passes with `-D` into the
    // (forked) test JVM. When `pactbroker.url` is unset (local) the @PactBroker provider test is
    // @EnabledIfSystemProperty-skipped and git-pact above stays the fallback.
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

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3; fleet rollout #1266). Weekly +
// manual via pitest.yml, advisory — never a per-PR gate. info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.account.domain.*")
    targetTests = setOf("com.openbank.account.domain.*", "com.openbank.account.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.account.domain.*Kt")
}
