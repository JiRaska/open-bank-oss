// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0220 first slice: domain layer only. No Quarkus plugin, no REST, no persistence, no
// deployment — deliberately, not by omission. A `version.txt` is what makes a module a released
// component (ADR-0029 D1); this one has none yet, so release-please and gitops both ignore it.
// The infrastructure layer (REST resource, Postgres, Kafka outbox, Dockerfile, gitops manifests)
// is a follow-up PR, scoped separately because it carries its own security review (mTLS certs,
// OPA policy, network policy) that a domain-only change should not ride in on.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    id("openbank.static-analysis")
    id("openbank.dependency-vulnerability-pins")
    `java-library`
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    api(libs.kotlin.stdlib)
    implementation(project(":openbank-libs-domain"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// openbank.static-analysis pins detekt's own languageVersion to 21 (JVM-version-detection quirk
// in its IntelliJ-derived parser) — that is unrelated to the module's compile/runtime toolchain,
// which openbank-libs-domain fixes at 25. Omitting this here left Gradle pick whatever JVM was on
// the PATH, which is 21 on the CodeQL tracing runner but not locally, so CodeQL's build step could
// not resolve against libs-domain's 25 while everything else stayed green.
kotlin {
    jvmToolchain(25)
}
