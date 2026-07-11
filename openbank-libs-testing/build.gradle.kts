// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("openbank.static-analysis")
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

    // Test-framework surface the conformance kits expose in their public abstract classes —
    // `api` so a consuming service's testImplementation(project(":openbank-libs-testing"))
    // pulls these transitively without redeclaring them.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj)

    // JAX-RS + CDI security annotation types the authz conformance kit reflects over
    // (jakarta.ws.rs.GET/POST/.../jakarta.annotation.security.PermitAll/RolesAllowed).
    // No Quarkus BOM here (this module isn't a Quarkus service), so pinned directly —
    // same coordinates openbank-libs-runtime already uses for the identical types.
    api("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    api("jakarta.annotation:jakarta.annotation-api:3.0.0")

    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
