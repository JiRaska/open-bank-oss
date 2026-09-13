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
    // Micrometer + Prometheus registry: customer-edge had no MeterRegistry, so it
    // exposed no /q/metrics surface and the openbank-services PodMonitor scraped a
    // 404 -> TargetDown. Adding this wires /q/metrics on the management port like
    // the rest of the fleet (ADR-0077 / ADR-0079).
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Redis: pending-onboarding store for four-eyes auto-resume (ADR-0072), keyed by caseId;
    // also backs the WebAuthn RP challenge/credential store (ADR-0066 F2) on the same instance.
    implementation(libs.quarkus.redis.client)
    // WebAuthn RP verification (ADR-0066 F2 native passkey) — registration/authentication
    // ceremony crypto (attestation + assertion). Pinned old on purpose; see libs.versions.toml.
    implementation(libs.webauthn4j.core)
    // ADR-0192 screen feedback: screenshots go to object storage via the shared ObjectStorePort
    // (ADR-0161). openbank-libs-runtime compiles S3ObjectStore against the AWS SDK as compileOnly,
    // so a service selecting openbank.objectstore.backend=s3 must bring the SDK itself — and the
    // port's suspend API needs coroutines on the runtime classpath (the edge is otherwise
    // coroutine-free; it is used from a @Blocking worker thread via runBlocking).
    implementation(libs.aws.sdk.s3)
    implementation(libs.kotlinx.coroutines.core)

    implementation(project(":openbank-libs"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.quarkus.junit5)
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.redpanda)
    // Provider-side Pact verification (ADR-0063, issue #2322): replays copilot's
    // CustomerEdgeContractConsumerTest against a real booted instance.
    testImplementation(libs.pact.provider)
    // Consumer-side Pact (ADR-0063, issue #2990): customer-edge is a CONSUMER of account-service's
    // delegated-payment authorization decision — the call that decides whether a delegate may
    // debit a shared account. Nothing else can catch a wrong path or parameter name there: the
    // edge's UpstreamClient is a plain HTTP client with no generated stub, so a unit test proves
    // only that the edge agrees with itself (#2269). Replayed by
    // account-service's AccountPactFolderProviderVerificationTest on every PR.
    testImplementation(libs.pact.consumer)
    // Synthesizes the customer JWT (party_id claim) @TestSecurity alone cannot set for a
    // quarkus-oidc-backed resource server — same mechanism openbank-mcp-service already uses.
    testImplementation(libs.quarkus.test.security.oidc)
    // CustomerEdgeMissingParamStatusIT (#3624): the missing-parameter defect lives in JAX-RS
    // parameter injection, so it is only observable over real HTTP — a unit test calling the
    // handler supplies the very argument the framework does not.
    testImplementation(libs.rest.assured.kotlin)
}

// Pact: read consumer pacts from the shared pacts/ dir at the repo root (git-pact, ADR-0063).
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not propagate).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

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
                    minValue = 37 // measured 41.8% (223/534) at introduction
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
