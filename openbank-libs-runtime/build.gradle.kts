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
    // Domain module — pure Kotlin/JVM; re-exported as api() so dependents on runtime
    // also see domain types without an explicit dependency.
    api(project(":openbank-libs-domain"))

    // EXCEPTION: BearerTokenClientHeadersFactory implements microprofile-rest-client's
    // ClientHeadersFactory. Shipped transitively as api() — see openbank-libs build.gradle.kts
    // for the full rationale (NoClassDefFoundError on services without rest-client extension).
    api("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")

    // Framework APIs — compileOnly (services provide impls via Quarkus platform BOM).
    // Versions MUST equal what quarkus-bom:3.33.2 ships.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    // @InterceptorBinding / @Nonbinding for the Authorize and FeatureFlag annotations, which
    // moved here from libs-domain in #3670 (CDI is framework — ADR-0122's runtime side).
    // Declared explicitly rather than leaned on transitively via cdi-api.
    compileOnly("jakarta.interceptor:jakarta.interceptor-api:2.2.0")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("org.eclipse.microprofile.config:microprofile-config-api:3.1")
    compileOnly("org.jboss.logging:jboss-logging:3.6.2.Final")
    compileOnly("io.quarkus:quarkus-redis-client:3.33.2")
    compileOnly("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")
    compileOnly("io.quarkus:quarkus-hibernate-reactive-panache-kotlin:3.33.2")
    compileOnly("io.quarkus:quarkus-scheduler:3.33.2")
    compileOnly("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1.1")
    compileOnly("io.micrometer:micrometer-core:1.14.5")
    compileOnly("io.quarkus:quarkus-security:3.33.2")
    compileOnly("io.quarkus:quarkus-arc:3.33.2")

    // S3ObjectStore (ADR-0161 D2) compiles against the real AWS SDK v2 `s3` module
    // (S3Presigner ships in the same artifact — no separate presigner dependency).
    // compileOnly, matching the convention above: this is NOT part of the Quarkus
    // platform BOM, so bundling it as implementation()/api() here would put the whole
    // AWS SDK on every consuming service's runtime classpath whether or not that service
    // ever selects the s3 backend. A service that sets openbank.objectstore.backend=s3
    // must add libs.aws.sdk.s3 (or an equivalent) to its OWN build.gradle.kts.
    compileOnly(libs.aws.sdk.s3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.2.0")
    testImplementation("io.quarkus:quarkus-security:3.33.2")
    testImplementation("org.jboss.resteasy:resteasy-core:6.2.12.Final")
    testImplementation("org.jboss.logging:jboss-logging:3.6.2.Final")
    testImplementation("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    testImplementation("io.micrometer:micrometer-core:1.14.5")
    // RedisApprovalStoreTest drives the REAL store — the four-eyes self-approval guard is the
    // single fleet-wide enforcement point for segregation of duties (#3349), and until that test
    // existed deleting it left every suite green. Both are compileOnly above, so the test source
    // set needs them explicitly; same pattern as quarkus-security and the FT API here.
    testImplementation("io.quarkus:quarkus-redis-client:3.33.2")
    testImplementation("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    // ResilientCallMetrics classifies CircuitBreakerOpenException; the API is compileOnly above.
    testImplementation("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1.1")
    // Test-only: WorkflowLivenessMetricNamingTest checks the dotted meter name against Micrometer's
    // REAL PrometheusNamingConvention rather than trusting the hand-rolled dot -> underscore
    // rendering that the sentinel's PromQL depends on. The registry itself is never used at runtime
    // here — each service brings quarkus-micrometer-registry-prometheus itself.
    testImplementation("io.micrometer:micrometer-registry-prometheus:1.14.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

kover {
    reports {
        filters {
            excludes {
                classes("com.openbank.libs.web.ServiceInfoResource")
                classes("com.openbank.libs.persistence.outbox.PanacheOutboxEntity")
                classes("com.openbank.libs.persistence.outbox.AbstractOutboxEntity")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 50
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
