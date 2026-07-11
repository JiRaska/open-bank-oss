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
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    implementation(libs.quarkus.scheduler)
    testImplementation(libs.quarkus.junit5)
    // @TestSecurity for the ADR-0034 Phase 5 advisory-mode authz regression test.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Boot smoke-test (SwiftBootSmokeIT): per-job Testcontainers Postgres + Valkey and the
    // in-memory Kafka connector, so a real Quarkus boot + Flyway runs in CI (issue #578).
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.pact.consumer)
    testImplementation(libs.pact.provider)
}

// Pact: write generated consumer contracts to pacts/ and forward broker config.
tasks.withType<Test> {
    // CI hang #3 (#2404): `@DisabledIfEnvironmentVariable` is evaluated AFTER Quarkus starts
    // QuarkusTestResource containers (quarkusio/quarkus#21555) — Quarkus ignores the JUnit
    // condition and boots anyway. SwiftBootSmokeIT hangs at boot for the full 45-min job
    // timeout, stalling the entire fleet Services CI run. Gradle-level filter prevents Quarkus
    // from discovering the class in CI; the IT still runs locally (where CI is unset).
    if (System.getenv("CI") == "true") {
        filter { excludeTestsMatching("*IT") }
    }
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

    // SwiftBootSmokeIT is a @QuarkusTest — Quarkus's BeforeAllCallback fires before JUnit5 evaluates
    // @DisabledIfEnvironmentVariable, so the full Quarkus boot + Testcontainers (Postgres + Valkey) still
    // starts in CI despite the annotation. Boot hangs 37+ min on the runner pool → job timeout.
    // Gradle-level exclusion prevents JUnit5 from ever discovering the class, so Quarkus never boots.
    // The class still runs locally (CI env var is not set outside GHA). Re-enable per #2404.
    //
    // SwiftMessagePactProviderVerificationTest: @PactBroker triggers broker network calls during
    // JUnit5 test-template expansion; the broker returns HTTP 404 (no consumer pact yet) and the
    // Pact client hangs waiting for a response — causing the same 43-min job timeout pattern.
    // @Disabled alone is insufficient because Pact's extension invokes the broker before JUnit5
    // condition evaluation. Gradle-level exclusion prevents class discovery entirely.
    // Remove both exclusions once a consumer pact exists (re-enable per #2404).
    if (System.getenv("CI") == "true") {
        exclude("**/SwiftBootSmokeIT*")
        exclude("**/SwiftMessagePactProviderVerificationTest*")
        // SwiftEventPactConsumerTest: PactConsumerTestExt auto-publishes the generated pact
        // to pactbroker.url (forwarded from CI) in AfterTestTemplate — broker returns 401/hangs,
        // stalling the JVM for 30+ min. Exclude in CI; pact publishing runs as a dedicated step.
        // Re-enable once the pact-publish CI step is wired per #2404.
        exclude("**/SwiftEventPactConsumerTest*")
        // ClearingSimulatorPactConsumerTest (issue #468 edge 4): same PactConsumerTestExt
        // auto-publish hang as SwiftEventPactConsumerTest above — it runs fine locally.
        exclude("**/ClearingSimulatorPactConsumerTest*")

        // CI hang #4 (#2404) background: test JVM used to hang 43+ min AFTER all tests
        // completed, in TestWorker$3.run() → LauncherSession.close() → CustomLauncherInterceptor
        // .launcherSessionClosed() → FacadeClassLoader.close() → URLClassLoader.close() blocking
        // on JAR locks still held by ForkJoinPool threads. The fix at the time was
        // `prod.mode.tests=true`, which prevents FacadeClassLoader from ever being created —
        // safe ONLY because every @QuarkusTest in this module was excluded from CI above.
        //
        // SwiftResourceAuthzTest (ADR-0034 Phase 5, issue #266) is a real @QuarkusTest that DOES
        // need to run in CI, so that invariant no longer holds: `prod.mode.tests=true` disables
        // the QuarkusClassLoader machinery @QuarkusTest itself depends on, and every run fails
        // with "should have been loaded with a QuarkusClassLoader ... FacadeClassLoader not
        // correctly identifying this class as a QuarkusTest" (PR #418). Dropped here — every
        // other service in the fleet runs real @QuarkusTest classes in CI without this hang, so
        // the deadlock was specific to an installed-but-unused FacadeClassLoader, not to
        // FacadeClassLoader use in general. quarkus.devservices.enabled=false stays: it only
        // suppresses Quarkus's auto-provisioned dev services, unrelated to the classloader bug,
        // and SwiftResourceAuthzTest brings its own Testcontainers resource explicitly.
        jvmArgs("-Dquarkus.devservices.enabled=false")
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
                    // Ratchet floor at measured LINE coverage (64.3%) minus headroom. Lifted from
                    // 22 (#1130 no-regression baseline) by adding application/mapper/dispatcher/DTO
                    // unit tests; remaining gap is the DB-bound repository layer (needs IT).
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
