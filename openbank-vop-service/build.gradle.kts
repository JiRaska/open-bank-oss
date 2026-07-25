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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    // Valkey-backed per-requester rate limit (ADR-0132 shape). Not throughput management: VoP is
    // a name oracle by construction, so the rate is the enumeration control (threat model §4.1).
    // Shared, not in-process — a local counter gives an attacker limit x replicas.
    implementation(libs.quarkus.redis.client)
    // ADR-0171 §4: the payee name lives two hops away — account-service (IBAN -> partyId) then
    // party-service (partyId -> legal/trading name). account-service holds no holder name at all.
    // The oidc-client filter mints the openbank-services M2M token both hops require.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Consumer-driven contract test (ADR-0063, issue #2255 dimension C3): vop is a real consumer of
    // party-service's GET /api/v1/parties/{id} — hop 2 of the ADR-0171 §4 name resolution.
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020). Set from the measured value at introduction with
                    // ~5pt headroom; raise-only from here.
                    minValue = 55
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write the generated consumer contract into the repo-root pacts/ dir (git-pact, ADR-0063) so
// the provider replays it from its own pact loader with no broker in the loop. The consumer test
// rewrites the file on every run and the result is committed; pact-drift-check.yml fails the build
// if the committed JSON and a fresh regeneration diverge.
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not
// propagate). The pactbroker.* keys are forwarded so `_service-ci.yml`'s publish step behaves the
// same way here as in every other consumer module.
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
