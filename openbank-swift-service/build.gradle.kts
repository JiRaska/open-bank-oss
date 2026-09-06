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

// -Pswift.boot.it=true lifts BOTH CI exclusions below, for the isolated `swift-boot-it-probe`
// workflow only (#2320 item 1: does the 37-min boot stall still reproduce?). That question cannot
// be answered from a developer machine — locally `CI` is unset, so the class already passes there,
// and the failing environment IS the runner pool. It also cannot be answered by narrowing the
// blanket `*IT` filter to SwiftBootSmokeIT alone: that filter also swallows SwiftOutboxClaimIT,
// which is itself a @QuarkusTest + @QuarkusTestResource — the same
// Quarkus-boots-before-JUnit-evaluates-the-condition shape — so narrowing would likely relocate
// the stall rather than remove it.
//
// The flag is deliberately NOT wired into any automatic trigger. It exists so the probe workflow
// can run one class under its own short `timeout-minutes`, isolated from the fleet matrix, where a
// wrong answer costs a few runner-minutes instead of a stalled full-fleet run (#2039).
// Do not set it in services-ci / _service-ci; re-enabling for real is #2320 item 3, and needs the
// probe's answer first.
// Pact: write generated consumer contracts to pacts/ and forward broker config.
tasks.withType<Test> {
    // Gradle's default test-JVM heap is 512m (the account-service comment at
    // openbank-account-service/build.gradle.kts documents it and asks to be told when a second
    // module OOMs — this is that signal). swift-service's suite boots Quarkus repeatedly AND
    // runs SwiftPactFolderProviderVerificationTest, which loads every pact naming this service
    // as provider into the same fork. Measured 2026-09-06: two unrelated PRs (#8781, #8697)
    // died in the same minute — `OutOfMemoryError: Java heap space` across ClassGraph-worker /
    // vert.x-eventloop / Finalizer threads ~3 minutes into the suite, after which the JVM hung
    // in GC until the 45-minute job timeout (#8781) or the pact verification Timed out
    // downstream of the GC storm (#8697). Same 2g ceiling account/lending/product-catalog
    // already run with; a ceiling, not an allocation.
    maxHeapSize = "2g"

    // CI hang #3 (#2320) is GONE — measured, not assumed. `SwiftBootSmokeIT` used to hang 37+ min
    // at Quarkus boot on the runner pool (`@DisabledIfEnvironmentVariable` is evaluated AFTER
    // Quarkus starts QuarkusTestResource containers, quarkusio/quarkus#21555), so a blanket
    // `filter { excludeTestsMatching("*IT") }` kept JUnit from discovering it at all. Both
    // exclusions are gone as of #2320 item 3. The `swift-boot-it-probe` workflow ran each class
    // on ubuntu-latest with the exclusions lifted, 2026-07-26:
    //
    //     SwiftBootSmokeIT   exit 0, 257s (cap 12m)   run 30201804638
    //     SwiftOutboxClaimIT exit 0, 215s (cap 12m)   run 30202120687
    //
    // The blanket filter is why the second class matters: it was never about SwiftBootSmokeIT
    // alone. `SwiftOutboxClaimIT` is the #1201 regression test for two dispatchers racing
    // `claimProcessable`, itself a @QuarkusTest + Testcontainers, and it was swallowed by the same
    // wildcard — so narrowing the filter to the named class would have looked like the cheap fix
    // and quietly left a money-path race untested. Both run in CI now.
    //
    // The per-test timeout is the guard the exclusion used to be. If the boot stall ever returns,
    // JUnit kills the test at 8 minutes and the module goes RED, instead of the class sitting on a
    // runner until the 45-minute fleet job timeout takes the whole matrix down with it — the
    // failure mode that caused the exclusion in the first place. Fail fast, not fail wide.
    systemProperty("junit.jupiter.execution.timeout.default", "8m")

    // Pact rootDir + Pact Broker property forwarding centralised into
    // build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
    // (ADR-0250 Phase 2, issue #4414) — the timeout above and the CI-only jvmArgs below are the
    // service-specific parts that remain.

    // SwiftMessagePactProviderVerificationTest re-enabled 2026-07-23: the 404-hang reason is
    // gone — transaction-service publishes a consumer pact for openbank-swift-service on every
    // main push, so /for-verification returns content. The class stays
    // @EnabledIfSystemProperty(pactbroker.url)-gated, so it runs only in the main-push lane.
    // Scan-scope fixed 2026-07-23 (#1948): the verification was crashing on a whole-classpath
    // ClassGraph scan; the test now scopes MessageTestTarget to com.openbank.swift.contract. That
    // fix touched only src/test, which does not rebuild this service on main-push, so this
    // build-file note exists to re-trigger the provider verification (#1348 drain).
    // The `exclude("**/SwiftBootSmokeIT*")` that stood here is gone with the blanket `*IT` filter
    // above (#2320 item 3) — the stall it guarded against does not reproduce, measured 2026-07-26.
    if (System.getenv("CI") == "true") {
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
