// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.quarkus)
    id("openbank.static-analysis")
    id("openbank.dependency-vulnerability-pins")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.scheduler)
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.quarkus.jdbc.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin { jvmToolchain(25) }
