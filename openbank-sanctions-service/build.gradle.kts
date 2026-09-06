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
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // AuditEventTime — the ONE copy of the rule openbank-audit-service's AuditConsumer applies to
    // a domain-event payload, so this service's producer tests assert against the real contract
    // rather than a per-service restatement of it (#3914).
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)

    // Pact provider verification (issue #2255, C3): fx-service is a consumer of
    // POST /api/v1/sanctions/screen. @TestSecurity supplies the operator role Pact replays with.
    testImplementation(libs.pact.provider)
    testImplementation(libs.quarkus.test.security)
    // RestAssured: SanctionsOutboxAtomicityIT (#8353) drives POST /screen and POST /review over
    // real HTTP, the only way to exercise a reactive Panache write (a bare @QuarkusTest thread
    // carries no Vert.x context). Pact's own provider verification uses HttpTestTarget, so this
    // module had no RestAssured on its test classpath before. #8699 adds a second driver: the
    // partial-screen defect is only observable as an HTTP status, so that test drives the real
    // endpoint too.
    testImplementation(libs.rest.assured.kotlin)
}

// Pact: replay the committed git-pact contracts (ADR-0063) and forward broker config when CI
// supplies it, so a later broker migration needs no build change here.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Mutation testing (ADR-0030 D3, issue #265). 3 domain files (SanctionsCheck, SanctionsList,
// SanctionsEntry — screening/matching decision logic) — same "substantive domain math"
// criterion pitest.yml already uses to include sepa-payment/sepa-instant/domestic-payment/
// fraud (3 domain files each), not the "thin domain, 1-2 files" criterion that excludes
// clearing/swift/lending/sca/consent/billing/settlement. Weekly-scheduled + manual dispatch
// via pitest.yml, advisory — never a per-PR gate. Per-service plugin pin on purpose (rules.yaml
// money_path_depth): keeping it out of the shared version catalog avoids a fleet-wide rebuild.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.sanctions.domain.*")
    targetTests = setOf("com.openbank.sanctions.domain.*", "com.openbank.sanctions.application.usecase.*")
    // Advisory (ADR-0063): pitest.yml reports the score; the Gradle task itself must not
    // fail the run, so the threshold is 0. The workflow owns the 70% check.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.sanctions.domain.*Kt")
}
