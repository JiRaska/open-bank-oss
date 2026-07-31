// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared Temporal client wiring (ADR-0209 D1, issue #2572).
//
// WHY THIS IS ITS OWN MODULE AND NOT PART OF openbank-libs-runtime
// It was written into openbank-libs-runtime first, and that broke EVERY service that does
// not use Temporal — roughly 40 of them — with:
//
//   Build step ArcProcessor#registerBeans threw an exception:
//   java.lang.IllegalArgumentException: Producer method return type not found in index:
//   io.temporal.client.WorkflowClient
//
// openbank-libs-runtime is Jandex-indexed and every service consumes it, so Quarkus Arc
// resolves the type closure of every producer method it contains — including, for a service
// with no temporal-sdk on its classpath, a return type it cannot find. `compileOnly` hides
// the dependency from consumers' runtime classpaths but NOT the producer from the index.
//
// The rule this encodes: a Jandex-indexed library that every service consumes may only
// declare producers whose types every service also has. A producer for an optional
// dependency belongs in a module only its users depend on. Splitting it out is the whole
// fix — the code itself is unchanged.
//
// Licensed Apache-2.0 on purpose: 8 of the 14 consumers are AGPL-3.0-only modules, and
// `rules.yaml: agpl_modules.rule` forbids an Apache-2.0 module depending on an AGPL one,
// never the reverse. An AGPL library here would put the 6 Apache consumers in violation.

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
    // Framework APIs — compileOnly, matching openbank-libs-runtime's convention: consuming
    // services provide the impls via the Quarkus platform BOM. Versions MUST equal what
    // quarkus-bom:3.33.2 ships. @ConfigMapping/@WithDefault (io.smallrye.config) arrive
    // transitively from quarkus-arc.
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("io.micrometer:micrometer-core:1.14.5")
    compileOnly("io.quarkus:quarkus-arc:3.33.2")

    // isTransitive = false on purpose. This module imports no Quarkus platform BOM, so a
    // transitive resolve would drag in temporal-sdk's OWN grpc/guava/protobuf versions
    // (grpc 1.54.1, guava 31.1-jre) — coordinates the fleet resolves nowhere else and which
    // are therefore absent from gradle/verification-metadata.xml, failing the build on
    // dependency verification. All three below are ALREADY pinned there (the 14 consuming
    // services resolve them), so this adds no new verification entries. Only the type
    // signatures are needed here; each consumer brings the real temporal-sdk itself.
    compileOnly("io.temporal:temporal-sdk:1.25.1") { isTransitive = false }
    // Kotlin data classes as workflow payloads need the Kotlin module on the Temporal
    // JSON converter; consumers already ship it via quarkus-rest-jackson (#2749).
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // grpc excluded: temporal-sdk brings its own 1.54.1, which carries GHSA-cfgp-2977-2fmm
    // (high) and fails dependency-review. The converter test needs the payload/converter types,
    // not the service client, so this stays a test-only classpath without grpc.
    testImplementation("io.temporal:temporal-sdk:1.25.1") { exclude(group = "io.grpc") }
    // protobuf reached the test classpath via grpc; excluding grpc means naming it directly.
    // Version matches the fleet pin in openbank.dependency-vulnerability-pins.
    testImplementation("com.google.protobuf:protobuf-java:4.35.0")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    compileOnly("io.temporal:temporal-serviceclient:1.25.1") { isTransitive = false }
    compileOnly("com.uber.m3:tally-core:0.13.0") { isTransitive = false }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    testImplementation("io.micrometer:micrometer-core:1.14.5")
    // TemporalClientProducerLazinessTest constructs the producer; its `by lazy` delegate is a
    // synthetic class referencing io.temporal.client.WorkflowClient, which must be LOADABLE
    // (not connectable) at construction. Same isTransitive = false rationale as above — and
    // the resulting absence of guava is what makes the falsification of that test legible.
    testImplementation("io.temporal:temporal-sdk:1.25.1") { isTransitive = false }
    testImplementation("io.temporal:temporal-serviceclient:1.25.1") { isTransitive = false }
    testImplementation("com.uber.m3:tally-core:0.13.0") { isTransitive = false }
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
                // 20, not the 50 openbank-libs-runtime uses, and the low number is the honest
                // one rather than a waiver. This module is 13 measurable lines: 3 are the bean
                // + the @Produces accessor, and 10 are the body of the `by lazy` delegate, which
                // builds real WorkflowServiceStubs against a Temporal frontend. Executing those
                // 10 in a unit test would mean either standing up a frontend or asserting
                // against a mock of the SDK's builders — the latter proves only that the mock
                // was called in the order the test says, which is the kind of test this repo
                // treats as vacuous. What IS assertable is that the delegate does not run at
                // construction, and TemporalClientProducerLazinessTest asserts exactly that.
                // Ratchet-only still applies from here: raise this if the module grows testable
                // logic, never lower it.
                bound {
                    minValue = 20
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
