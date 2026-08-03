// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
    api(libs.kotlin.reflect)
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.datatype.jsr310)

    // UUIDv7 generation for time-ordered identifiers (ADR-0106).
    // Internal detail of domain.identifiers.Ids — services mint ids via EntityId.random() factories.
    implementation(libs.java.uuid.generator)

    // ADR-0122: this module must stay framework-free (no jakarta/Quarkus/JBoss imports).
    // Guarded by .github/scripts/check-libs-domain-purity.sh. Anything needing CDI
    // (@Authorize, @FeatureFlag), HTTP (OpaSidecarPolicyDecisionPoint, FlagdProvider) or
    // a logging facade (OutboxDispatch, LoggingExposurePublisher, StaticServiceTokenProvider)
    // lives in openbank-libs-runtime under the same package names.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Property-based tests on Money arithmetic invariants (ADR-0011 L1, issue #469). Same
    // version pin as openbank-ledger-service/openbank-balance-service's JournalEntryPropertyTest/
    // BalancePropertyTest — kept as a direct GAV like theirs rather than the shared catalog, since
    // only the handful of services with money-invariant property suites need it.
    testImplementation("io.kotest:kotest-property:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 30
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
