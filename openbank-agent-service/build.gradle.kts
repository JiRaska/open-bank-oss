// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

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
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    // Service-to-service auth: acquire a client-credentials token (openbank-services)
    // and attach it as a Bearer on the outbound MCP tool calls (ADR-0031 / ADR-0034).
    // Catalog alias maps to quarkus-rest-client-oidc-filter (the new REST client's OIDC filter).
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.smallrye.kafka)
    // Cross-replica PoP nonce replay guard (issue #4728) — same shared TTL-cache mechanism
    // already deployed for ~30 services fleet-wide (RedisIdempotencyStore/RedisApprovalStore
    // in openbank-libs-runtime are the closest siblings). See RedisNonceStore.
    implementation(libs.quarkus.redis.client)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Proposals store: plain Agroal JDBC (sync MCP path) + Flyway. NOT Hibernate ORM —
    // openbank-libs ships reactive Panache entities that the ORM entity scanner can't index
    // on this service's (non-reactive) classpath. See AgentProposal / ProposalService.
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // First fleet adopter of the shared OTel trace-contract kit. It keeps the agent's existing
    // span test coupled to an evidence-safe assertion surface reusable by E2E/synthetic tests.
    testImplementation(project(":openbank-libs-testing"))
    // D3b SVID tests build an EC CA + leaf cert at runtime (no committed private keys → gitleaks-clean).
    // Declared directly (not via the shared catalog) so this one-service test dep does not trigger a
    // full-fleet rebuild on the shared libs.versions.toml.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // First integration test (ADR-0011 L2): isolated PostgreSQL per JVM (#578 pattern).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
}

// Package the live ADR-0148 registry prompts onto the classpath so the assistant and oversight
// agent load their system prompts from the registered files at runtime instead of inline constants.
// This copy is derived from openbank-libs/governance/prompts/ — never hand-edit it here.
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance/prompts/compliance-officer")) {
        include("oversight.v1.md")
        into("governance-prompts/compliance-officer")
    }
    from(rootProject.file("openbank-libs/governance/prompts/ui-assistant")) {
        include("system.v3.md", "catalog-review.v1.md")
        into("governance-prompts/ui-assistant")
    }
}

// Coverage floor (ADR-0020, ratchet-only — sweep #466: was a placebo minValue = 0
// gate that could never fail). Measured 75.8% LINE coverage (1344/1772) at
// introduction, no filter excludes; floor set at 70, raise-only from here.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 70
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
