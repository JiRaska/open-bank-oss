// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

// Build-time stamping of openbank-build-info.properties. Values come from
// libs.versions.toml so a Quarkus / Kotlin / Gradle bump in one place flows
// straight through to the runtime info endpoint.
//
// Build time is taken from java.time.Instant so the build does not depend on a
// `date` binary being present (the Docker Alpine base image has BusyBox `date`,
// but pure JVM is portable). Git commit is read via `git rev-parse` when the
// binary is available; in a Docker build context that strips .git we fall
// back to "unknown" — passing the real commit through a build-arg is a
// follow-up if reproducible attribution becomes a hard requirement.
val buildTimeStamp: String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))

val gitCommitStamp: String = runCatching {
    val proc = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }
    val exit = proc.result.get().exitValue
    if (exit == 0) proc.standardOutput.asText.get().trim().ifBlank { "unknown" } else "unknown"
}.getOrDefault("unknown")

// Read versions directly from libs.versions.toml instead of via the
// `libs.versions.*` typed accessor — that accessor is generated at the root
// composite level and is not visible inside openbank-libs when this project
// is built standalone (Quarkus services include this build via includeBuild,
// see ADR 0014).
val libsVersionsToml: Map<String, String> = providers.fileContents(
    layout.projectDirectory.file("gradle/libs.versions.toml"),
).asText.get().lineSequence()
    .takeWhile { !it.startsWith("[libraries]") }
    .mapNotNull { line ->
        Regex("""^(\w[\w.-]*)\s*=\s*"([^"]+)"\s*$""").find(line.trim())?.let {
            it.groupValues[1] to it.groupValues[2]
        }
    }
    .toMap()

val kotlinFromCatalog = libsVersionsToml["kotlin"] ?: error("kotlin version missing in libs.versions.toml")
val quarkusFromCatalog = libsVersionsToml["quarkus"] ?: error("quarkus version missing in libs.versions.toml")

tasks.processResources {
    inputs.property("kotlinVersion", kotlinFromCatalog)
    inputs.property("quarkusVersion", quarkusFromCatalog)
    inputs.property("buildTime", buildTimeStamp)
    inputs.property("gitCommit", gitCommitStamp)
    filesMatching("openbank-build-info.properties") {
        filter(
            org.apache.tools.ant.filters.ReplaceTokens::class,
            "tokens" to mapOf(
                "kotlinVersion" to kotlinFromCatalog,
                "quarkusVersion" to quarkusFromCatalog,
                "quarkusLts" to "true",
                "quarkusSupportUntil" to "2027-03-25",
                "gradleVersion" to gradle.gradleVersion,
                "buildTime" to buildTimeStamp,
                "gitCommit" to gitCommitStamp,
                "libsVersion" to project.version.toString(),
            ),
        )
    }
}

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
tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

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
