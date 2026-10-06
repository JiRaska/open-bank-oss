// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
    testImplementation(libs.pact.provider)
    // Integration tests for the real external adapters (ClickHouse / Vault / Apicurio) drive the
    // adapters' actual HTTP path against throwaway Docker containers — see *IT.kt. They self-skip when
    // Docker is absent (@Testcontainers(disabledWithoutDocker = true)), so the default offline build is
    // unaffected for existing integration-tagged tests; run those with `-PwithDocker`.
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
// Existing Docker-backed `@Tag("integration")` adapter ITs are excluded unless `-PwithDocker` is
// passed. The untagged provider Pact replay below runs in the default test lane against ClickHouse.
tasks.test {
    useJUnitPlatform { if (!project.hasProperty("withDocker")) excludeTags("integration") }
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

// Analytics-sink does not apply openbank.quarkus-service (it has no OLTP database), so register
// the same isolated broker-verification lane explicitly. The always-running @PactFolder replay
// stays in `test`; this task runs only the broker twin and publishes its result on main-push.
val providerPactTestSourceSet = sourceSets.create("providerPactTest") {
    kotlin.srcDir("src/test/kotlin")
    kotlin.include("**/*BrokerProviderVerificationTest.kt")
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets.main.get().output +
        sourceSets.test.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}
tasks.register<Test>("providerPactTest") {
    group = org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verify analytics-sink provider pacts against the broker on main-push."
    testClassesDirs = providerPactTestSourceSet.output.classesDirs
    classpath = providerPactTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    listOf(
        "pactbroker.url", "pactbroker.auth.username", "pactbroker.auth.password",
        "pactbroker.enablePending", "pactbroker.providerBranch", "pact.verifier.publishResults",
        "pact.provider.version", "pact.provider.branch", "pact.provider.tag",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    shouldRunAfter(tasks.named("test"))
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
