// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury instrument engine (ADR-0344 D1): schedule generation, accrual, cash-flow projection
// and pricing math for the bank's own deals, as one framework-free library under the ADR-0317
// split. It depends on the kernel's DayCount, BusinessCalendar, BusinessDayConvention and the
// ADR-0318 rounding registry in openbank-libs-domain and on nothing else.

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
    // DayCount, BusinessCalendar, CurrencyCode and RoundingPolicy are part of this module's API.
    api(project(":openbank-libs-domain"))

    // NO framework dependencies here — same rule as openbank-libs-domain (ADR-0002, #3670).

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.kotest:kotest-property:5.9.1")
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
                    minValue = 96 // 98.11 % measured at introduction (2026-10-19), minus 2
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
