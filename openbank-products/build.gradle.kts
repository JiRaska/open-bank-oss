// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// openbank-products (ADR-0352 §5, §11): the product CONTENT module. A Kotlin DSL that exists only at
// build time compiles the reference deposit products to IR v1 JSON (the artifact, committed under
// ir/ and the only thing the catalog stores), their golden scenarios (public conformance vectors),
// property tests over ProductArb, and the `obp` CLI. A released component (version.txt), so every
// product change has a release and a changelog entry. No framework, no I/O at runtime beyond the CLI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    id("openbank.static-analysis")
    id("openbank.dependency-vulnerability-pins")
    application
}

group = "com.openbank"
version = file("version.txt").readText().trim()

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-finance"))
    // The golden runner, ProductArb and the money-conservation assertion live in libs-testing
    // (ADR-0352 §5); `obp test` runs the same runner the CI gate runs.
    implementation(project(":openbank-libs-testing"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.kotest:kotest-property:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.openbank.products.cli.Obp")
    applicationName = "obp"
}

tasks.test {
    useJUnitPlatform()
    // The CLI's `compile --check` and the drift test read the committed IR relative to the module root.
    workingDir = projectDir
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // 97.8 % (443/453) measured at introduction (2026-10-23), minus headroom; raise-only (ADR-0020)
                    minValue = 95
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
