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
tasks.withType<Test> {
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
