// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.quarkus)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.kover)
    // Static analysis gate (detekt + ktlint, ratchet via baselines) — same
    // convention the services get through openbank.quarkus-service.
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
}
group = "com.openbank"
version = "0.1.0-SNAPSHOT"

// This service does not apply openbank.quarkus-service because it has no OLTP database.
// Publish the same exact-source build fact as the convention-managed services.
val sourceCommit = providers.environmentVariable("SOURCE_COMMIT").map { it.trim() }.filter { it.isNotEmpty() }
    .orElse(providers.environmentVariable("GITHUB_SHA").map { it.trim() }.filter { it.isNotEmpty() })
    .orElse(
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() },
    )
val generatedBuildInfo = layout.buildDirectory.file("generated/service-build-info/openbank-service-build.properties")
val generateServiceBuildInfo by tasks.registering {
    inputs.property("sourceCommit", sourceCommit)
    outputs.file(generatedBuildInfo)
    doLast {
        val commit = sourceCommit.get().trim()
        check(Regex("[0-9a-fA-F]{40}").matches(commit)) {
            "${project.name}: a full source commit is required (SOURCE_COMMIT, GITHUB_SHA, or git HEAD)"
        }
        generatedBuildInfo.get().asFile.apply {
            parentFile.mkdirs()
            writeText("git.commit=$commit\n")
        }
    }
}
tasks.named<Copy>("processResources") {
    from(generatedBuildInfo)
    dependsOn(generateServiceBuildInfo)
}
val verifyServiceBuildInfo by tasks.registering {
    group = "verification"
    description = "Verify that the running analytics sink JAR contains its full source commit."
    dependsOn("quarkusBuild")
    doLast {
        val appJars = layout.buildDirectory.dir("quarkus-app/app").get().asFile
            .listFiles { file -> file.extension == "jar" }.orEmpty()
        check(appJars.size == 1) { "${project.name}: expected one Quarkus application JAR" }
        ZipFile(appJars.single()).use { jar ->
            val packagedCommit = jar.getEntry("openbank-service-build.properties")
                ?.let { jar.getInputStream(it).bufferedReader().readText() }
                ?.let { Regex("(?m)^git\\.commit=([0-9a-fA-F]{40})$").find(it)?.groupValues?.get(1) }
            check(packagedCommit == sourceCommit.get().trim()) {
                "${project.name}: full source commit was not packaged in the application JAR"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyServiceBuildInfo) }

repositories {
    // GCS mirror of Maven Central first (#849) — shared NAT egress IP gets
    // 429-throttled by Central during fleet-wide build storms; 404 falls through.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

// NOTE: This service is intentionally *stateless on the OLTP side*. It owns no Postgres
// database — its store of record is the analytics warehouse (ClickHouse), fed from the
// same domain events the transactional outbox already publishes (ADR-0022). So unlike the
// other services we deliberately DROP: hibernate-reactive-panache, reactive-pg-client,
// jdbc-postgresql and flyway. Keeping them would imply an OLTP coupling that does not exist
// and would be the very "reporting load on the operational system" this design avoids.
dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.kotlin)
    // Structured console logging — this module applies the Quarkus plugin directly rather than
    // openbank.quarkus-service, so it declares the extension that convention plugin supplies.
    implementation("io.quarkus:quarkus-logging-json")
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // The INITIAL_LOAD backfill source sweeps account-service's registry over REST rather than
    // reading another service's database. Both are Quarkus extensions already used by 44 modules
    // here, not new third-party libraries.
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs"))
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Integration tests for the real external adapters (ClickHouse / Vault / Apicurio) drive the
    // adapters' actual HTTP path against throwaway Docker containers — see *IT.kt. They self-skip when
    // Docker is absent (@Testcontainers(disabledWithoutDocker = true)), so the default offline build is
    // unaffected; run them explicitly with `-PwithDocker` (see tasks.test below).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    // Boot IT proving the Kafka consumer joins the intended group.id (issue #686) — see
    // AnalyticsEventsConsumerGroupIdBootIT.kt. Also backs AnalyticsSinkBootSmokeIT (issue #709).
    testImplementation(libs.testcontainers.redpanda)
}
kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}
// The default `test` run stays unit-only and infra-free (preserves the offline-buildable promise):
// the Docker-backed `@Tag("integration")` adapter ITs are excluded unless `-PwithDocker` is passed.
tasks.test {
    useJUnitPlatform { if (!project.hasProperty("withDocker")) excludeTags("integration") }
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSkipConfigs(listOf("testCompileClasspath", "testRuntimeClasspath", "annotationProcessor", "kapt"))
    setProjectType("application")
    setSchemaVersion("1.5")
}

// Coverage floor (ADR-0020, ratchet-only — sweep #466: this module previously had no kover
// plugin at all). Measured 56.7% LINE (538/949) at introduction, no filter excludes;
// ~5 pt headroom, raise-only from here. Kover auto-wires koverVerify into check when a
// verify{} rule is present (this module does not apply openbank.quarkus-service).
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 51
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
