// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// openbank-simulation — the Deterministic Simulation Testing (DST) harness (ADR-0100).
//
// This is TOOLING, not a released service: it has NO version.txt, is NOT in
// release-please-config.json, and is never built into a container. It is a pure-JVM
// (JDK-only, no Quarkus) Gradle module that virtualises the money-path domain semantics
// — double-entry ledger posting, balance/overdraft, the payment saga and its
// compensation, ledger→balance event projection — under a seeded, deterministic
// scheduler + fault injector, and asserts the Layer-3 global invariants from ADR-0100
// after every simulated step. A failing run is reproducible from its seed.
//
// Engine choice (ADR-0100 "Pure JVM simulation"): the lowest-cost, zero-tooling first
// rung. It exercises the domain + application semantics faithfully (built on the real
// `openbank-libs` primitives — Money, SagaStateMachine); it cannot catch JVM-threading
// or OS-level non-determinism (that is the deferred Antithesis option).
//
// CI note: editing ANY `build.gradle.kts` — including a comment like this one, which
// changes no dependency at all — matches the `**/build.gradle.kts` path filter in
// dependency-submission.yml and dependency-review.yml, so it triggers a full ~11 min
// fleet dependency resolution. That is deliberate (the filter cannot inspect intent),
// but it means a comment-only touch here is not free. See issue #2833.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    // Same static-analysis gate (detekt + ktlint) the rest of the fleet runs.
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461). This module pulls
    // several services in via project(...) and independently re-resolves their transitive
    // graph (a producer's own resolutionStrategy.force() does not propagate to a project(...)
    // consumer), so it needs the same floor those services get through openbank.quarkus-service.
    id("openbank.dependency-vulnerability-pins")
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    // GCS mirror of Maven Central first (#849) — shared NAT egress IP gets
    // 429-throttled by Central during fleet-wide build storms; 404 falls through.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    // The harness is built on the REAL shared domain primitives so the simulated
    // semantics track production (Money arithmetic + scale rules, the saga transition
    // engine). openbank-libs exposes kotlin-stdlib/coroutines/jackson transitively (api).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    // The harness drives the REAL ledger domain aggregate (JournalEntry.validateBalance /
    // bookedDeltas / reverse) rather than a re-model, so the simulated postings exercise
    // production code (ADR-0100 — build on the real system). The ledger-service domain layer is
    // framework-free (ADR-0002), so only its POJOs are pulled onto the classpath, not a runtime.
    implementation(project(":openbank-ledger-service"))

    // ADR-0100 Layer 2: the harness now exercises the REAL Balance aggregate from
    // balance-service rather than a faithful re-model. The domain layer is framework-free
    // (ADR-0002), so only the domain POJOs land on the classpath.
    implementation(project(":openbank-balance-service"))
    implementation(project(":openbank-transaction-service"))

    // Issue #267 (ADR-0100 full-service adoption): the harness also drives the REAL
    // `SepaPayment` status machine (sepa-payment) and `Settlement` status machine
    // (settlement-service), rather than a re-model of either. Both domain packages
    // are framework-free (ADR-0002 — no jakarta/quarkus/temporal imports), so only
    // the domain POJOs land on the classpath, not a runtime.
    implementation(project(":openbank-sepa-payment"))
    implementation(project(":openbank-settlement-service"))

    // ADR-0143 phase 2d: the harness drives the REAL billing-service domain value objects
    // (AssessedFee, FeeJournalCommand, FeeReversalCommand — com.openbank.billing.domain) so
    // FeeBillingScenario exercises the exact idempotency-key/GL-direction shape production
    // billing-service posts, not a re-model. Only the domain package is framework-free
    // (ADR-0002); the module also carries Quarkus/Panache infrastructure classes, same as
    // openbank-ledger-service above — pulling the whole project(...) is the established
    // convention here (only the domain POJOs are actually referenced from this module).
    implementation(project(":openbank-billing-service"))

    // Issue #667 (E2E money-path): the harness also drives the REAL interest domain —
    // `InterestAccrual`/`InterestCapitalization` and the ADR-0033 `WithholdingTaxPolicy`
    // (statutory §36/§38d withholding with whole-CZK rounding). The domain package is
    // framework-free (ADR-0002), so only the domain POJOs land on the classpath.
    implementation(project(":openbank-interest-service"))

    // Issue #667 (E2E money-path): the harness also drives the REAL statement-service domain —
    // `StatementPeriod`/`PeriodCloseStatus` and the ADR-0035/0078 `ReconciliationPolicy`
    // (fail-closed period-boundary reconciliation: a period is closed only when the computed
    // closing balance matches balance-service's independently reported one). The domain package
    // is framework-free (ADR-0002), so only the domain POJOs land on the classpath.
    implementation(project(":openbank-statement-service"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    // ADR-0115: let CI / a deep manual run dial the seed count (`-Pseed.count=N`). Absent the
    // property the test falls back to its built-in default, so a plain `:test` stays reproducible.
    providers.gradleProperty("seed.count").orNull?.let { systemProperty("seed.count", it) }
}

// Coverage floor (ADR-0020, ratchet-only — sweep #466). The DST harness is tooling, but its
// invariant checkers ARE the safety net for the money-path domain semantics — an untested
// checker is a checker that silently stops checking. Measured 96.0% LINE (457/476) at
// introduction; floor 90, raise-only from here.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 90
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
