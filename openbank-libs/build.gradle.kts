// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
    alias(libs.plugins.kover)
    // Static analysis gate (detekt + ktlint, ratchet via baselines) — same
    // convention the services get through openbank.quarkus-service.
    id("openbank.static-analysis")
    `java-library`
    `maven-publish`
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    // GCS mirror of Maven Central first (#849) — shared NAT egress IP gets
    // 429-throttled by Central during fleet-wide build storms; 404 falls through.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.kotlin.reflect)
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.datatype.jsr310)

    // UUIDv7 generation for time-ordered identifiers (ADR-0106). `implementation`, not `api`:
    // JUG is an internal detail of `domain.identifiers.Ids` — services mint ids via the typesafe
    // `EntityId.random()` factories and never reference JUG directly, so it must not leak onto the
    // service compile classpath. Apache-2.0, no transitive deps.
    implementation(libs.java.uuid.generator)

    // EXCEPTION to the `compileOnly` rule below: this one ships transitively (`api`).
    // `BearerTokenClientHeadersFactory` is an @ApplicationScoped bean that implements
    // microprofile-rest-client's `ClientHeadersFactory`. Unlike JAX-RS/CDI/config — which
    // EVERY Quarkus service pulls in via resteasy/arc/core — the rest-client extension is
    // opt-in: only services making OUTBOUND calls (e.g. account→balance) declare
    // `quarkus-rest-client-reactive`. A service that depends on this lib but has no
    // rest-client extension (party, audit, balance, …) cannot load the `ClientHeadersFactory`
    // interface, so Arc fails to even REMOVE the (unused) bean → NoClassDefFoundError crashes
    // app startup (surfaced fleet-wide once a shared-workflow change forced a full rebuild).
    // Shipping just the API jar transitively makes the interface always loadable: Arc removes
    // the bean cleanly where rest-client is absent, and dedups against the extension where it
    // is present. Version 4.0 == quarkus-bom:3.33.2's rest-client-api, so no LinkageError.
    api("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")

    // These API/SPI artifacts are `compileOnly`: the running services provide the
    // implementations via `enforcedPlatform(libs.quarkus.bom)`. The pinned versions
    // below MUST equal what the Quarkus platform BOM (quarkus = 3.33.2 in
    // libs.versions.toml) actually ships, otherwise the library compiles against a
    // different API than the one present at runtime (latent LinkageError/NoSuchMethod).
    // Do NOT let Dependabot push these ABOVE the Quarkus version — they track the
    // platform, they do not lead it. Verified against quarkus-bom:3.33.2.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("org.eclipse.microprofile.config:microprofile-config-api:3.1")
    compileOnly("org.jboss.logging:jboss-logging:3.6.2.Final")
    compileOnly("io.quarkus:quarkus-redis-client:3.33.2")
    compileOnly("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")
    // Active-record base for PanacheOutboxEntity. compileOnly like the rest: the running
    // service provides the impl via the Quarkus platform BOM. Pinned to quarkus 3.33.2.
    compileOnly("io.quarkus:quarkus-hibernate-reactive-panache-kotlin:3.33.2")
    // ADR-0049 D3: AbstractOutboxDispatcher documents @Scheduled and MicroProfile Fault Tolerance
    // annotations for concrete subclasses. compileOnly keeps the contract visible at compile time
    // without shipping the impls (provided at runtime via each service's Quarkus platform BOM).
    compileOnly("io.quarkus:quarkus-scheduler:3.33.2")
    compileOnly("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1.1")
    // ADR-0077: ObservabilityProducer uses MeterRegistry. compileOnly — Quarkus micrometer
    // extension ships MeterRegistry at runtime in every service that carries it.
    compileOnly("io.micrometer:micrometer-core:1.14.5")
    // ADR-0034 D5: AuthorizeInterceptor reads SecurityIdentity.roles to propagate JWT roles
    // into the OPA input. compileOnly — every service with quarkus-oidc (which is all of them)
    // transitively ships quarkus-security at runtime. Pinned to quarkus-bom:3.33.2.
    compileOnly("io.quarkus:quarkus-security:3.33.2")
    // ADR-0100: DefaultClockProducer marks its @Produces Clock with io.quarkus.arc.DefaultBean so a
    // service's own ClockProducer overrides it without ambiguity. compileOnly — quarkus-arc is core
    // CDI, shipped transitively at runtime by every service. Pinned to quarkus-bom:3.33.2.
    compileOnly("io.quarkus:quarkus-arc:3.33.2")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // SecurityContext is a compileOnly JAX-RS type in main; tests mock it, so the API
    // must be on the test classpath explicitly (compileOnly does not leak to test).
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    // SecurityIdentity is compileOnly in main (quarkus-security); tests that mock it need the
    // interface on the test classpath.
    testImplementation("io.quarkus:quarkus-security:3.33.2")
    // AuthorizeInterceptorTest constructs ForbiddenException / ServiceUnavailableException;
    // those JAX-RS constructors call RuntimeDelegate.getInstance() which requires a registered
    // implementation. RESTEasy core provides one via ServiceLoader. Pinned to the same
    // RESTEasy 6.x that Quarkus 3.33.2 ships — avoids a "no RuntimeDelegate" ClassNotFoundException
    // in unit tests that have no Quarkus runtime on the classpath.
    testImplementation("org.jboss.resteasy:resteasy-core:6.2.12.Final")
    // OutboxDispatch (and other libs objects) hold a jboss-logging Logger initialised at
    // class-load; compileOnly in main does not leak to test, so unit-testing them needs it here.
    testImplementation("org.jboss.logging:jboss-logging:3.6.2.Final")
    // FeatureFlagInterceptor implements a CDI interceptor; unit-testing the @AroundInvoke body
    // needs InvocationContext + Instance on the test classpath. compileOnly cdi-api in main does
    // not leak to test; cdi-api transitively brings jakarta.interceptor-api. Pinned to the main
    // compileOnly version (quarkus 3.33.2).
    testImplementation("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    // DomainMetrics unit test drives a real SimpleMeterRegistry; micrometer-core is
    // compileOnly in main (provided by the Quarkus extension at runtime) so it must be
    // added explicitly to the test classpath.
    testImplementation("io.micrometer:micrometer-core:1.14.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Build-time stamping of openbank-build-info.properties. Values come from
// libs.versions.toml so a Quarkus / Kotlin / Gradle bump in one place flows
// straight through to the runtime info endpoint.
//
// Build time is taken from java.time.Instant so the build does not depend on a
// `date` binary being present (the Docker Alpine base image has BusyBox `date`,
// but pure JVM is portable). Git commit is read via `git rev-parse` when the
// binary is available; in a Docker build context that strips .git we fall
// back to "unknown" — passing the real commit through a build-arg is a
// follow-up if reproducible attribution becomes a hard requirement.
val buildTimeStamp: String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))

val gitCommitStamp: String = runCatching {
    val proc = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }
    val exit = proc.result.get().exitValue
    if (exit == 0) proc.standardOutput.asText.get().trim().ifBlank { "unknown" } else "unknown"
}.getOrDefault("unknown")

// Read versions directly from libs.versions.toml instead of via the
// `libs.versions.*` typed accessor — that accessor is generated at the root
// composite level and is not visible inside openbank-libs when this project
// is built standalone (Quarkus services include this build via includeBuild,
// see ADR 0014).
val libsVersionsToml: Map<String, String> = providers.fileContents(
    layout.projectDirectory.file("gradle/libs.versions.toml"),
).asText.get().lineSequence()
    .takeWhile { !it.startsWith("[libraries]") }
    .mapNotNull { line ->
        Regex("""^(\w[\w.-]*)\s*=\s*"([^"]+)"\s*$""").find(line.trim())?.let {
            it.groupValues[1] to it.groupValues[2]
        }
    }
    .toMap()

val kotlinFromCatalog = libsVersionsToml["kotlin"] ?: error("kotlin version missing in libs.versions.toml")
val quarkusFromCatalog = libsVersionsToml["quarkus"] ?: error("quarkus version missing in libs.versions.toml")

tasks.processResources {
    inputs.property("kotlinVersion", kotlinFromCatalog)
    inputs.property("quarkusVersion", quarkusFromCatalog)
    inputs.property("buildTime", buildTimeStamp)
    inputs.property("gitCommit", gitCommitStamp)
    filesMatching("openbank-build-info.properties") {
        filter(
            org.apache.tools.ant.filters.ReplaceTokens::class,
            "tokens" to mapOf(
                "kotlinVersion" to kotlinFromCatalog,
                "quarkusVersion" to quarkusFromCatalog,
                "quarkusLts" to "true",
                "quarkusSupportUntil" to "2027-03-25",
                "gradleVersion" to gradle.gradleVersion,
                "buildTime" to buildTimeStamp,
                "gitCommit" to gitCommitStamp,
                "libsVersion" to project.version.toString(),
            ),
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // Byte Buddy officially supports up to Java 23; Java 25 raises an IAE unless
    // experimental mode is enabled. Opt in here so the test suite works on the
    // toolchain (jvmToolchain(25)) without requiring a Byte Buddy version bump.
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

// Make the coverage gate part of `check` (and therefore `build`), so the existing
// per-module CI step (`./gradlew :openbank-libs:build`) enforces it with no extra
// workflow wiring. koverVerify already depends on test, so ordering is handled.
tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

// Coverage (ADR-0020). Kover over JaCoCo: Kotlin-native, understands inline
// functions / coroutines, no separate agent. The verify gate is a *regression
// floor*, not an aspiration — it fails the build only if coverage drops below
// what we already have, so it can ratchet up but never silently rot down.
// Bump the floor whenever a PR lands new tests; never lower it.
kover {
    reports {
        filters {
            excludes {
                // Build-time stamp loader + its REST surface: read resource files
                // and JVM runtime properties, so unit tests would assert nothing
                // meaningful. Exercised end-to-end by the service @QuarkusTest layer.
                classes("com.openbank.libs.util.BuildInfo")
                classes("com.openbank.libs.web.ServiceInfoResource")
                // JPA mapped-superclasses: no logic beyond column mapping + a trivial toEntry()
                // projection, exercised end-to-end by each service's @QuarkusTest outbox IT
                // (a real reactive DB). Unit-testing them would only assert the obvious.
                classes("com.openbank.libs.persistence.outbox.PanacheOutboxEntity")
                classes("com.openbank.libs.persistence.outbox.AbstractOutboxEntity")
            }
        }
        verify {
            rule {
                bound {
                    // Floor sits just under the current 39.7% line coverage. Raise it
                    // as tests land; do not lower it to make a red build go green.
                    minValue = 39
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

kotlin {
    // Pin to JDK 25 (Temurin LTS) to match every openbank-* service. Without
    // this the build falls back to the system default (e.g. JDK 26 after a
    // host upgrade) and services compiled for jvmToolchain(25) refuse to
    // consume the resulting JAR with "compatible with JVM runtime version 26
    // or newer" resolution failure.
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
