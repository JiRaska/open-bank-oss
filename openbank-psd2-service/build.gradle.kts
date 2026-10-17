// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    // Inline version (not the shared catalog) so enabling mutation testing stays path-scoped to
    // this service. 1.19.0 supports Gradle 9.
    id("info.solidsoft.pitest") version "1.19.0"
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // Outbound M2M bearer for the consent-service REST client (issue #1500) — oidc-client
    // client_credentials on openbank-services, attached by OidcClientRequestReactiveFilter.
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.redis.client)
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache-kotlin")
    // Hibernate Reactive needs the Vert.x reactive Postgres driver at runtime; Flyway (over the
    // blocking JDBC driver) runs the V1/V2 migrations on boot. Without these the service has Panache
    // entities + migrations but no SQL client — it builds and unit-tests green (no boot-time DB) but
    // CrashLoops in-cluster with "No reactive SQL client implementation". Mirrors dispute-service.
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Boot smoke-test (Psd2BootSmokeIT): per-job Testcontainers Postgres + Valkey and the
    // in-memory Kafka connector, so a real Quarkus boot + Flyway runs in CI (issue #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":openbank-libs-testing"))
    // Consumer-driven contract test (ADR-0063, issue #2255 dimension C3): psd2 is a real consumer of
    // tpp-registry's eIDAS licence gate, GET /api/v1/tpp-registry/check.
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write the generated consumer contract into the repo-root pacts/ dir (git-pact, ADR-0063) so
// the provider replays it from @PactFolder("../pacts") with no broker in the loop. The consumer test
// rewrites the file on every run and the result is committed; pact-drift-check.yml fails the build
// if the committed JSON and a fresh regeneration diverge.
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not
// propagate). The pactbroker.* keys are forwarded so CI's publish step behaves as it does elsewhere.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Forked test JVM heap. With PisIdempotencyFingerprintIT's profile this module boots Quarkus once
// more, and the default 512m test heap then dies with OutOfMemoryError mid-suite (locally, and in CI
// as a hung executor cancelled at the job timeout). Same per-module override as account-service;
// see its comment for why this is not a fleet default.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

// Mutation testing on the money-path domain + application layers (ADR-0063 / ADR-0030 D3, #13149).
// Weekly + manual via pitest.yml — never a per-PR gate. mutationThreshold is a RATCHET: measured
// score minus 2 points, raised as surviving mutants get killed, never lowered.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.psd2.domain.*", "com.openbank.psd2.application.*")
    // First run (#13149) with domain/application-only targetTests scored billing 0% and card-processing 4%:
    // their unit tests live in the package root, usecase/ and scheme/. Run every unit test and drop the
    // Quarkus/contract/e2e ones, which need infrastructure and would only add minutes per mutant.
    targetTests = setOf("com.openbank.psd2.*")
    excludedTestClasses = setOf(
        "com.openbank.psd2.integration.*", "com.openbank.psd2.it.*", "com.openbank.psd2.contract.*",
        "com.openbank.psd2.e2e.*", "com.openbank.psd2.testsupport.*",
    )
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.psd2.domain.*Kt", "com.openbank.psd2.application.*Kt")
}
