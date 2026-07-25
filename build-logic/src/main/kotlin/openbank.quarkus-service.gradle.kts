// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Convention plugin applied by every openbank-*-service (and equivalent modules).
// Centralises the boilerplate that used to be copy-pasted across 31 build.gradle.kts
// files: plugin applications, Kotlin/allOpen config, docker-java version pinning,
// Testcontainers environment, and the CycloneDX SBOM task (ADR-0029 D1).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("io.quarkus")
    id("org.cyclonedx.bom")
    id("org.jetbrains.kotlinx.kover")
    // Static analysis gate (detekt + ktlint, ratchet via per-module baselines).
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
}

// All services share the same Maven groupId (ADR-0029 D2).
group = "com.openbank"

// Single source of truth for the service version: version.txt in the service's
// own directory. The Quarkus Gradle plugin propagates this into
// quarkus.application.version at build time, which ServiceInfoResource
// (/api/v1/info) and the X-API-Version response header report at runtime.
version = file("version.txt").readText().trim()

repositories {
    // GCS mirror of Maven Central FIRST (#849): the in-cluster runner pool shares one
    // NAT egress IP, and fleet-wide build storms get that IP 429-throttled by Central.
    // The mirror answers from Google's CDN; a 404 there falls through to Central.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

kotlin {
    // Pin to JDK 25 (Temurin LTS) across the whole fleet. Without an explicit
    // toolchain the build falls back to the system default JDK which may differ
    // between dev workstations and CI runners → "compiled for JVM runtime version
    // N" resolution failures when consuming openbank-libs.
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    // Quarkus CDI / JAX-RS requires non-final classes.
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    // @QuarkusTest beans must also be open for Quarkus test injection.
    annotation("io.quarkus.test.junit.QuarkusTest")
    // Hibernate Reactive @WithSession methods must be open so Quarkus can
    // intercept them with the session management interceptor.
    annotation("io.quarkus.hibernate.reactive.panache.common.WithSession")
    // JPA entity classes and mapped superclasses must be open for Hibernate
    // proxying / lazy-loading interceptors.
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    // Quarkus Scheduler intercepts @Scheduled methods, so the bean class must be open.
    annotation("io.quarkus.scheduler.Scheduled")
}

// Pin the three docker-java artefacts to a single coherent version across
// all configurations. Testcontainers pulls a different docker-java-api
// transitively than the Quarkus Dev Services extension, causing
// NoSuchMethodError at test runtime without this pin.
configurations.all {
    resolutionStrategy {
        force("com.github.docker-java:docker-java-api:3.7.1")
        force("com.github.docker-java:docker-java-transport:3.7.1")
        force("com.github.docker-java:docker-java-transport-zerodep:3.7.1")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Testcontainers Docker endpoint. Inherit the ambient DOCKER_HOST (the ephemeral
    // ARC dind pod exposes the daemon on the unix socket, ADR-0053; a dev workstation
    // sets its own) and fall back to the standard unix socket. The previous hard pin
    // to tcp://localhost:2375 was the retired OrbStack/EC2 endpoint and is wrong on the
    // ARC dind runners — it broke the per-job isolated test infra (Testcontainers)
    // rollout (fleet sweep, issue #578). TESTCONTAINERS_RYUK_DISABLED avoids the Ryuk
    // reaper that requires a privileged container the sandbox runners do not grant;
    // test resources tear their own containers down in stop().
    environment(
        "DOCKER_HOST",
        providers.environmentVariable("DOCKER_HOST").orElse("unix:///var/run/docker.sock").get(),
    )
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")

    // Committed pacts are derived data (ADR-0063), so a regenerated pact must be AUTHORITATIVE —
    // it has to be able to remove an interaction, not only add one. pact-jvm's default writer
    // MERGES a freshly generated pact into whatever JSON already sits at pact.rootDir, so a
    // hand-edited or stale interaction survives regeneration untouched and the pact-drift gate's
    // `git diff -- pacts/` only ever sees additions. Proven locally on 2026-07-25: a committed
    // tamper of openbank-card-issuance-service-openbank-product-catalog.json came back as
    // "132 insertions(+), 0 deletions(-)" with the tampered interaction still present and the
    // correct one appended alongside it. With overwrite the same tamper diffs 3(+)/3(-) and the
    // tampered text is gone. Set here (not per module) so the 21 services that write pacts cannot
    // drift apart on it.
    systemProperty("pact.writer.overwrite", "true")
}

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSkipConfigs(listOf("testCompileClasspath", "testRuntimeClasspath", "annotationProcessor", "kapt"))
    setProjectType("application")
    setSchemaVersion("1.5")
}

// Coverage gate (ADR-0020, ratchet-only — sweep #466). koverVerify is wired into
// check fleet-wide: a module with a kover verify{} rule gets its floor enforced, a
// module without rules passes trivially — so a module cannot silently opt out of
// the ratchet. The previous default here DISABLED koverVerify and required
// per-service re-enable boilerplate; that made "ungated" and "gated with floor 0"
// indistinguishable from a real gate in a green build. Floors live in each module's
// build.gradle.kts and only ever go up.
tasks.named("check") {
    dependsOn("koverVerify")
}
