// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0219 D4's compile-time wiring assertion: a detekt RuleSetProvider loaded onto every
// service's forked detekt CLI classpath (openbank.static-analysis.gradle.kts), never applied to
// itself as a dependency (that would be a self-referential project dependency).

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("openbank.static-analysis")
    `java-library`
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val detektVersion = libs.versions.detekt.get()

dependencies {
    // provided at runtime by the detekt CLI classpath this rule set is loaded onto — never
    // bundled, or the CLI's own detekt-api classes collide with ours on the classpath.
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:$detektVersion")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.gitlab.arturbosch.detekt:detekt-api:$detektVersion")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:$detektVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
