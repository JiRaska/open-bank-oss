// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Lending bounded context split out of openbank-libs-domain (ADR-0317 phase 1): packages
// `lending` (incl. `lending.compliance`, `lending.origination`) and `decision`, PACKAGE NAMES
// UNCHANGED so no consumer import moved. Framework-free exactly like libs-domain (ADR-0122,
// ADR-0002). The dependency direction is libs-lending -> libs-domain, never the reverse.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
    alias(libs.plugins.kover)
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
    `java-library`
    `maven-publish`
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    // Money and the governance compliance-pack vocabulary are part of this module's public API.
    api(project(":openbank-libs-domain"))

    // NO framework dependencies here — same rule as openbank-libs-domain (ADR-0002, #3670).

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // Property tests over random schedules (Amortization small-principal invariants).
    testImplementation("io.kotest:kotest-property:5.9.1")
    // Property tests over the origination graph (ADR-0211 D1): random walks must never throw
    // and every reached state must stay reachable per the policy. jqwik runs on the JUnit
    // Platform, so the existing useJUnitPlatform() test task picks it up unchanged.
    testImplementation("net.jqwik:jqwik:1.9.2")
    // jqwik 1.9.2's Gradle module metadata drags JUnit 5.11.3 / Platform 1.11.3, one patch
    // above the catalog line: its platform-commons `requires` pulls the 5.11.3 BOM in
    // transitively and that BOM re-manages jupiter upward. Enforce the catalog BOM so this
    // module's tests stay on the fleet-verified 5.11.0 line (jqwik is patch-compatible with
    // Platform 1.11.0, and the BOM still supplies the opentest4j/apiguardian it wants).
    testImplementation(enforcedPlatform(libs.junit.bom))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 93 // 95.53 % measured on the moved code (2026-09-26), minus 2
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

kotlin {
    // Track the public JVM surface; check/build reject unreviewed ABI drift.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
