// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
    alias(libs.plugins.kover)
    // Static analysis gate (detekt + ktlint, ratchet via baselines) — same
    // convention the services get through openbank.quarkus-service.
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
    `java-library`
    `maven-publish`
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
    // ADR-0122 Phase 1: openbank-libs is now an umbrella re-export of the two sub-modules.
    // Fleet dependencies on openbank-libs remain unchanged in Phase 1; Phase 2 sweeps
    // individual services to depend directly on domain or runtime as appropriate.
    api(project(":openbank-libs-domain"))
    api(project(":openbank-libs-runtime"))

    // No direct source in this module any more — all source lives in domain/runtime.
    // Test dependencies kept for the umbrella integration smoke tests (none yet; placeholder).
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// openbank-build-info.properties is stamped by openbank-libs-domain, next to BuildInfo which
// reads it: services that depend on domain/runtime directly (ADR-0122 Phase 2) never get this
// umbrella JAR, so a resource stamped here reported every stack version as `unknown`.

tasks.test {
    useJUnitPlatform()
    // Byte Buddy officially supports up to Java 23; Java 25 raises an IAE unless
    // experimental mode is enabled. Opt in here so the test suite works on the
    // toolchain (jvmToolchain(25)) without requiring a Byte Buddy version bump.
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

// Make the coverage gate part of `check` (and therefore `build`), so the existing
// per-module CI step (`./gradlew :openbank-libs:build`) enforces it with no extra
// workflow wiring. koverVerify already depends on test, so ordering is handled.

// Coverage (ADR-0020). Kover over JaCoCo: Kotlin-native, understands inline
// functions / coroutines, no separate agent. The verify gate is a *regression
// floor*, not an aspiration — it fails the build only if coverage drops below
// what we already have, so it can ratchet up but never silently rot down.
// Bump the floor whenever a PR lands new tests; never lower it.
// Umbrella module has no source; skip the coverage gate — it is enforced per sub-module.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 0
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

kotlin {
    // Pin to JDK 25 (Temurin LTS) to match every openbank-* service. Without
    // this the build falls back to the system default (e.g. JDK 26 after a
    // host upgrade) and services compiled for jvmToolchain(25) refuse to
    // consume the resulting JAR with "compatible with JVM runtime version 26
    // or newer" resolution failure.
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
