// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import java.time.Duration

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
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    // Four-eyes ApprovalStore backing store (ADR-0155) — this service had no Redis client
    // before; wired onto the existing shared payments-namespace Redis instance (gitops).
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    // ADR-0120 Phase 1: Temporal-backed payment orchestration (flag-gated, default off). Inline
    // version matching openbank-settlement-service so enabling it stays path-scoped to this service.
    // Shared TemporalConfig + TemporalClientProducer (ADR-0209 D1, #2572).
    implementation(project(":openbank-libs-temporal"))
    implementation("io.temporal:temporal-sdk:1.25.1")
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.wiremock.standalone)
    // Consumer-driven contract tests (ADR-0063 P1 ledger, P2 fx + balance cover).
    testImplementation(libs.pact.consumer)
    // Provider verification (ADR-0063 P2 Batch A): account-service calls POST /api/v1/transactions.
    testImplementation(libs.pact.provider)
    // CI infra sweep (#578): isolated PostgreSQL + Redpanda (Kafka) per test JVM.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    // Assertion-backed trace contracts: the integration test owns the exporter and emits
    // a machine-readable marker only after its span/attribute assertions pass.
    testImplementation(project(":openbank-libs-testing"))
    // ADR-0120 Phase 1: in-memory Temporal test environment for the payment workflow tests.
    testImplementation("io.temporal:temporal-testing:1.25.1")
    testImplementation("io.grpc:grpc-inprocess:1.68.1")
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
                // StartupEvent observer (ADR-0120 P1): can't be unit-tested without @QuarkusTest;
                // only exercised when the Temporal flag is enabled.
                classes("com.openbank.transaction.application.workflow.PaymentWorkerRegistrar")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 85
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write generated consumer contracts to pacts/ and forward broker config for provider
// verification (ADR-0063 P1/P2). pactbroker.* props are injected by CI with -D.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

tasks.withType<Test> {
    // Fail fast, not fail wide (issue #5940). This module boots a real Kafka producer against a
    // Redpanda Testcontainer, and Quarkus tears that container down whenever the set of
    // @QuarkusTestResource classes changes between test classes. A producer left over from the
    // previous boot then retries against a dead port. On job 96416555756 that left the job with no
    // output but reconnect attempts from 12:09:46Z until it was cancelled at the 45-minute fleet
    // job timeout at 12:43:38Z, and `build/test-results/` was never written — so the job burned a
    // full runner slot and produced no JUnit XML to diagnose from.
    //
    // The Kafka client bounds in src/test/resources/application.properties are the direct fix. This
    // is the backstop for anything else in this module that can stall past a test boundary: JUnit
    // kills the test at 8 minutes and the module goes RED with a named test, instead of sitting on
    // a runner until the fleet job timeout takes the whole matrix down with it. Same guard and same
    // value as openbank-swift-service, added there for the same failure shape (#2320 item 3).
    systemProperty("junit.jupiter.execution.timeout.default", "8m")

    // The JUnit timeout above only fires while a test is RUNNING, and the #5940 hang was not that:
    // the last test activity was at 12:09:46Z and the job was cancelled at 12:43:38Z, with seven
    // `Terminate orphan process: pid (java)` at teardown. A Kafka producer's network thread is
    // non-daemon and keeps reconnecting for as long as the producer object is open, so a leaked
    // Quarkus application from a previous boot can hold its JVM alive after every test has
    // finished — a state no per-test timeout can observe.
    //
    // Gradle's task timeout does cover it: it bounds the whole task, forked JVM included. At 25
    // minutes the module fails with a named task, 20 minutes inside the 45-minute fleet job
    // timeout, which is what lets the `build/test-results/**/*.xml` upload actually run. In the
    // #5940 job it did not: `No files were found with the provided path:
    // openbank-transaction-service/build/test-results/`. A cancelled job produces no report at
    // all, so the cost is not just the runner slot — it is that nothing survives to diagnose from.
    timeout.set(Duration.ofMinutes(25))

    // Gradle's default test-JVM heap is 512m, which this module never overrode. That was
    // survivable while it had one or two @QuarkusTestProfile classes; MergeSweepApprovalBindingIT's
    // FourEyesProfile is now another distinct profile forcing its OWN Quarkus boot in the same
    // forked JVM, alongside the module's Testcontainers-backed ITs, Redis/Temporal/Kafka clients,
    // WireMock, and MockK-based unit tests — the exact multi-Quarkus-boot-per-JVM shape
    // account-service and lending-service already hit and fixed the same way. In the full-module
    // run that manifested as `java.lang.OutOfMemoryError` late in the suite (observed directly in a
    // Kafka consumer heartbeat thread), which reads misleadingly downstream: client-side calls that
    // happen to be mid-flight during the GC thrash trip their own read-timeout as a
    // `SocketTimeoutException` (MergeSweepApprovalBindingIT's two four-eyes steps, both fast and
    // fully stubbed in isolation), while the OOMing non-daemon Kafka consumer thread keeps the JVM
    // alive well past that until this task's own 25-minute timeout above force-kills it.
    //
    // Deliberately per-module rather than a fleet default — see account-service's identical comment.
    maxHeapSize = "2g"
}

// Mutation testing on the money-path domain (ADR-0063 / ADR-0030 D3; fleet rollout #1266). Weekly +
// manual via pitest.yml, advisory — never a per-PR gate. info.solidsoft.pitest 1.19.0 supports Gradle 9.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.openbank.transaction.domain.*")
    targetTests = setOf("com.openbank.transaction.domain.*", "com.openbank.transaction.application.usecase.*")
    // Advisory for now (ADR-0063): the pitest.yml job reports the score and warns below 70%; the
    // Gradle task itself must not fail the run, so the threshold is 0. Raise to block later.
    mutationThreshold = 0
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    excludedClasses = setOf("com.openbank.transaction.domain.*Kt")
}
