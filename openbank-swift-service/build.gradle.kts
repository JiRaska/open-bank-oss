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
    // CI hang #3 (#2320): `@DisabledIfEnvironmentVariable` is evaluated AFTER Quarkus starts
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
    // The class still runs locally (CI env var is not set outside GHA). Re-enable per #2320 —
    // note that means this service's only real boot test has never once gated a merge.
    //
    // SwiftMessagePactProviderVerificationTest re-enabled 2026-07-23: the 404-hang reason is
    // gone — transaction-service publishes a consumer pact for openbank-swift-service on every
    // main push, so /for-verification returns content. The class stays
    // @EnabledIfSystemProperty(pactbroker.url)-gated, so it runs only in the main-push lane.
    // Scan-scope fixed 2026-07-23 (#1948): the verification was crashing on a whole-classpath
    // ClassGraph scan; the test now scopes MessageTestTarget to com.openbank.swift.contract. That
    // fix touched only src/test, which does not rebuild this service on main-push, so this
    // build-file note exists to re-trigger the provider verification (#1348 drain).
    if (System.getenv("CI") == "true") {
        exclude("**/SwiftBootSmokeIT*")
        // SwiftEventPactConsumerTest and ClearingSimulatorPactConsumerTest USED to be excluded
        // here too, on the stated grounds that PactConsumerTestExt auto-publishes to
        // pactbroker.url in AfterTestTemplate and the broker 401s/hangs. That reason did not
        // hold (#2319). Two measurements: with CI=true and a broker URL set, both classes finish
        // in 17s and the log contains no publish attempt at all; and 26 other modules forward the
        // same pactbroker.* properties to their test JVM while running their consumer tests in CI
        // without incident. pact-jvm writes consumer pacts to pact.rootDir; publishing is a
        // separate step, not something the extension does on its own. Do not re-add the
        // exclusions without reproducing the hang first — while they were in place neither pact
        // was regenerated, and SwiftEventPactConsumerTest silently drifted to a `SETTLED` status
        // that SwiftStatus has never contained.

        // CI hang #4 (#2320) background: test JVM used to hang 43+ min AFTER all tests
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
