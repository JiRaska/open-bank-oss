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
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    // Persistence: reactive Panache + Postgres + Flyway, the fleet standard (openbank-libs is built on
    // reactive Panache, so a service that depends on it must use reactive too — a blocking ORM cannot
    // index the libs reactive entities). The Uni results are bridged to the suspend ports via the
    // Mutiny↔coroutine adapter; the Temporal activities already run on a Vert.x duplicated context.
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.jdbc.postgresql) // Flyway runs migrations over JDBC
    implementation(libs.quarkus.flyway)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Shared TemporalConfig + TemporalClientProducer (ADR-0209 D1, #2572).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    implementation(project(":openbank-libs"))
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.rest.assured.kotlin)
    // Git-pact for the Admin UI's bounded operator trigger (ADR-0063): the
    // consumer shape and provider replay protect the 202/workflowId contract.
    testImplementation(libs.pact.consumer)
    testImplementation(libs.pact.provider)
}

tasks.withType<Test> {
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")
}

// Package the ADR-0148 prompt registry onto the classpath so LlmDiagnosisAdapter loads its system
// prompt from the registered file (byte-for-byte, so the prompt_hash resolves) instead of an inline
// constant. The registry is the single source of truth (openbank-libs/governance/prompts/); this copy
// is derived — never hand-edit the packaged copy.
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance/prompts/flaky-test-hunter")) {
        include("*.md")
        into("governance-prompts/flaky-test-hunter")
    }
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
                    // Ratchet floor (ADR-0020): initial coverage from the domain-model test only;
                    // raise-only from here as adapters/activities gain tests.
                    minValue = 5
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
