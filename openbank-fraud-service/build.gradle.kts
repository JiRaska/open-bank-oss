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
    implementation(libs.quarkus.redis.client)
    // ADR-0220 D3.5 fraud-hold signal: FraudOutboxDispatcher (outbox drain) and
    // FraudHoldService.sweepExpired (TTL expiry) both need @Scheduled — first use in this service.
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    // ADR-0220 D3.5 fraud-hold signal: resolving partyId from accountId (AccountServiceClient)
    // needs an outbound M2M client, which fraud-service has never had before.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    // In-process ONNX Runtime serving (ADR-0139 phase-1b, OnnxFraudModel). Fraud-service-only for
    // now (single ML consumer) — kept out of the shared version catalog on purpose, same rationale
    // as the pitest plugin pin above, to avoid a fleet-wide rebuild for a single-service dependency.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.pact.consumer)
    // fraud-service is now also a pact PROVIDER (FraudPactProviderVerificationTest, #468) —
    // sepa-payment's fraud-score contract needs a provider-side replay, same as every other
    // service that carries both directions.
    testImplementation(libs.pact.provider)
}

// Pact: write generated consumer contracts to pacts/ and forward broker config.
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

kover {
    reports {
        filters {
            excludes {
                // Canonical money-path shape (ADR-0029 D3, mirrors openbank-ledger-service):
                // exclude only thin REST adapters (@Path) and reflection DTOs. Do NOT exclude
                // @ApplicationScoped — that is the application/use-case layer (the money logic)
                // and MUST count toward the floor.
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    // Ratchet floor at measured LINE coverage (92.4% over a small 105-line base)
                    // minus generous per-line headroom, with the @Path / @RegisterForReflection
                    // excludes. Early scaffold — no-regression baseline; raise as the service
                    // grows. (#1130 follow-up — gate enabled below.)
                    minValue = 85
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3). Weekly + manual via
// pitest.yml, advisory — never a per-PR gate. Per-service plugin pin on purpose (rules.yaml
// money_path_depth): keeping it out of the shared version catalog avoids a fleet-wide rebuild.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.fraud.domain.*")
    targetTests = setOf("com.openbank.fraud.domain.*", "com.openbank.fraud.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.fraud.domain.*Kt")
}
