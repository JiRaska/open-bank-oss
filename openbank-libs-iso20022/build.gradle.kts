// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Payments bounded context split out of openbank-libs-domain (ADR-0317 phase 1): packages
// `iso20022` (builders, readers, XSD validation + the schemas under src/main/resources) and
// `domain.payment`, PACKAGE NAMES UNCHANGED so no consumer import moved. Framework-free exactly
// like libs-domain (ADR-0122, ADR-0002): JDK javax.xml only. It may depend on libs-domain, never
// the reverse — today it needs nothing from it.

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
    api(libs.kotlin.stdlib)

    // NO framework dependencies here — same rule as openbank-libs-domain (ADR-0002, #3670).

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
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
                    minValue = 95 // 97.30 % measured on the moved code (2026-09-26), minus 2
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
