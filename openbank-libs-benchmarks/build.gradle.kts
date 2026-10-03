// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// JMH benchmarks for the shared libs (libs-domain, libs-runtime, libs-iso20022).
//
// NOT a released component and NOT shipped: no version.txt (release-please ignores it), no
// `maven-publish`, nothing depends on it, and its code lives only in the `jmh` source set. It has
// no Kover floor on purpose — a benchmark is measured by what it reports, not by line coverage.
//
// What is gated is ALLOCATION per operation (`gc.alloc.rate.norm`, B/op), which is a property of
// the code and reproduces across machines; wall-clock (ns/op) depends on the runner and is only
// ever reported. The comparison against `alloc-baseline.json` is
// `.github/scripts/check-bench-alloc-baseline.py`.
//
//   ./gradlew :openbank-libs-benchmarks:jmh          # writes build/reports/jmh/results.json
//   python3 .github/scripts/check-bench-alloc-baseline.py \
//       --results openbank-libs-benchmarks/build/reports/jmh/results.json

plugins {
    alias(libs.plugins.kotlin.jvm)
    // JMH subclasses @State classes and overrides nothing final: open them without `open` noise.
    alias(libs.plugins.kotlin.allopen)
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
    // Pinned here rather than in the shared version catalog: a catalog edit is a code-global
    // input that rebuilds and redeploys the whole fleet, and no service needs this plugin.
    id("me.champeau.jmh") version "0.7.3"
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    jmh(project(":openbank-libs-domain"))
    jmh(project(":openbank-libs-runtime"))
    jmh(project(":openbank-libs-iso20022"))
}

jmh {
    jmhVersion = "1.37"
    // The gate's contract: -f 1 -wi 3 -i 3 -prof gc -rf json, serial GC, fixed heap. Allocation
    // per op does not need long iterations to converge, so one second each keeps the whole run
    // to a few minutes.
    fork = 1
    warmupIterations = 3
    iterations = 3
    warmup = "1s"
    timeOnIteration = "1s"
    profilers = listOf("gc")
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    jvmArgs = listOf("-XX:+UseSerialGC", "-Xmx512m")
    failOnError = true
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") }
}
