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
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // The ISO 20022 message core (builders + reader + XSD validation). The simulator builds NO
    // messages of its own — it reuses the shared library so the wire format is identical to the
    // rail's (ADR-0104). No database: the simulator is a stateless, deterministic counterparty.
    implementation(project(":openbank-libs"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Provider-side verification of the pacs.008/pacs.002 contract rail services publish
    // (ADR-0063, issue #468). Git-pact (@PactFolder), matching ledger-service — no broker needed.
    testImplementation(libs.pact.provider)
}

kover {
    reports {
        filters {
            excludes {
                // Thin REST adapters (@Path) and reflection DTOs only — the deterministic decision
                // engine and the use-case orchestration MUST count toward the floor.
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact Broker verification (ADR-0092): forward the broker config CI passes with `-D` into the
// (forked) test JVM. Without this the properties reach the Gradle daemon and stop there, so the
// @PactBroker provider test is @EnabledIfSystemProperty(pactbroker.url)-skipped and pact-jvm
// logs "Skipping publishing of verification results ... not 'true'" — even on a main push where
// the workflow set PUBLISH_RESULTS=true. That is exactly how this module ended up with a Pact
// Broker version carrying no branch and no verification result, leaving its consumers
// permanently UNVERIFIED and undeployable (issue #3285). 14 of 17 providers already had this
// block; the correlation with the three that did not was exact.
tasks.withType<Test> {
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
