// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
    alias(libs.plugins.kover)
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
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.datatype.jsr310)

    // UUIDv7 generation for time-ordered identifiers (ADR-0106).
    // Internal detail of domain.identifiers.Ids — services mint ids via EntityId.random() factories.
    implementation(libs.java.uuid.generator)

    // Logging API used by OutboxDispatch, FlagExposure, StaticServiceTokenProvider.
    // compileOnly: jboss-logging is provided at runtime by every Quarkus app via quarkus-core.
    // Not a framework dependency — it is a logging facade that runs standalone.
    compileOnly("org.jboss.logging:jboss-logging:3.6.2.Final")

    // CDI interceptor binding annotations used by Authorize and FeatureFlag (@InterceptorBinding,
    // @Nonbinding). compileOnly: provided at runtime by every Quarkus app via quarkus-arc/cdi.
    compileOnly("jakarta.interceptor:jakarta.interceptor-api:2.2.0")
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // FlagdProviderTest and OpaSidecarPolicyDecisionPointTest drive real HttpClient calls over
    // a mock server; no framework needed — they are pure JVM.
    testImplementation("org.jboss.logging:jboss-logging:3.6.2.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
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
