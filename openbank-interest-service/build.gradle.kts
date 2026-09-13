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
    implementation(libs.quarkus.cache)
    // #999: book the withholding-tax cash leg to the finanční úřad via transaction-service, with an
    // openbank-services M2M token minted by the oidc-client filter.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    // #8352: AuditEventTime — the ONE shared copy of the rule `AuditConsumer.eventTime` applies to
    // a payload, so this service's own test can assert what its outbox event becomes in the audit
    // trail (EVENT vs INGEST). Restating the rule locally is what let the two withholding events
    // ship with no event time at all: a hand-kept second copy of an agreement between modules
    // moves with the first and keeps passing against a contract neither side honours.
    testImplementation(project(":openbank-libs-testing"))

    // CI infra sweep (#578): isolated PostgreSQL + Valkey(Redis) per test JVM
    // via Testcontainers. Kafka is already in-memory in the IT (no broker).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)

    // Consumer-driven contract for the ledger-service postJournal call the ADR-0033 §D
    // capitalization credit leg makes (ADR-0063). interest-service is the fifth ledger-posting
    // consumer; every other one already has a pact with openbank-ledger-service as provider.
    testImplementation(libs.rest.assured.kotlin)
    // InterestMissingParamStatusIT (#3104) drives `capitalize` over REAL HTTP behind @RolesAllowed —
    // the only layer at which an omitted @QueryParam can be observed at all.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.pact.consumer)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 49.4% (326/660) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 44
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write the generated consumer contract to pacts/ and forward broker config, matching
// transaction-service/lending-service/billing-service/settlement-service's tasks.withType<Test>
// block (ADR-0063 P1/P2). Without pact.rootDir the pact lands in the module's build/pacts and
// .github/workflows/pact-drift-check.yml never sees it.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3; money-path matrix
// extension, issue #3675). Weekly + manual via pitest.yml, advisory — never a per-PR gate.
// info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.interest.domain.*")
    targetTests = setOf("com.openbank.interest.domain.*", "com.openbank.interest.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.interest.domain.*Kt")
}
