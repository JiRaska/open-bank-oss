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
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.cache)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")

    testImplementation(libs.quarkus.junit5)
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    // @TestSecurity for the advisory-mode authz regression test (ADR-0034 Phase 5, issue #266).
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Boot smoke-test (LendingBootSmokeIT): per-job Testcontainers Postgres + Valkey and the
    // in-memory Kafka connector, so a real Quarkus boot + Flyway runs in CI (issue #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Consumer-driven contract test against ledger-service postJournal (ADR-0063 P2 Batch B).
    testImplementation(libs.pact.consumer)
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
                    // Coverage floor (ADR-0020, ratchet-only: floors only ever go up, never
                    // down). Issue #321's Q3 milestone asked for a minimum of 55; this PR's
                    // unit-test sweep measured 84% LINE coverage, well above that minimum —
                    // exceeding the milestone target is expected and encouraged under the
                    // ratchet policy, not a deviation from it. Floor pinned to 80, a few
                    // points below measured so the gate isn't brittle to one new branch in
                    // untested code.
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write generated consumer contracts to pacts/ and forward broker config for verification.
tasks.withType<Test> {
    // Gradle's default test-JVM heap is 512m, which no module in this fleet overrides. That was
    // survivable while lending-service had one @QuarkusTestProfile class; it now has two
    // (CustomerIntakeConfigInjectionTest and CompliancePackReplicaConvergenceIT), and each profile
    // forces its OWN Quarkus boot in the same forked JVM alongside Testcontainers. CI died with
    // `java.lang.OutOfMemoryError: Java heap space` inside the OTLP exporter's worker thread,
    // surfacing as a SocketTimeoutException on the convergence test's polling assertion — the
    // misleading part, because that test's own logic was correct. Same root cause and same fix as
    // openbank-account-service's `tasks.withType<Test>` block (see its comment for the account-service
    // occurrence — this is the second module to hit it, which is the documented signal to raise it
    // here rather than fleet-wide).
    //
    // Deliberately per-module rather than a fleet default: nothing measures test heap anywhere, so
    // a global bump would be an unmeasured ratchet applied to 50 modules to fix one.
    maxHeapSize = "2g"

    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")
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
