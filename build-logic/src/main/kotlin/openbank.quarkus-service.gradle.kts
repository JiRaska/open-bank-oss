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

// Shared config for EVERY Test task in the module — `test` plus the `providerPactTest` task
// registered below (ADR-0250 Phase 2). Deliberately `withType<Test>().configureEach` rather
// than `tasks.test { ... }`: the pact rootDir/broker-forwarding block used to be hand-copied
// into 36 individual build.gradle.kts files (issue #4414) purely so it would also apply to a
// service's own extra Test tasks — putting it here once, on every Test task fleet-wide, is what
// makes per-service copies removable at all.
tasks.withType<Test>().configureEach {
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
    // A shared Testcontainers resource emits a deliberately secret-free lifecycle
    // observation here. The CI envelope is evidence, not a container inventory:
    // ports, hosts, credentials and ids must never leave the test runner.
    val testIntelligenceRuntimeDir = layout.buildDirectory.dir("test-intelligence/runtime")
    environment("OPENBANK_TEST_EVIDENCE_DIR", testIntelligenceRuntimeDir.get().asFile.absolutePath)
    // Recorder output is append-only within one test task so concurrently managed resources do
    // not lose transitions. Reset only this generated task directory before each invocation:
    // otherwise a local re-run mixes prior lifecycle evidence into the next envelope.
    doFirst {
        project.delete(testIntelligenceRuntimeDir)
    }

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

    // Pact rootDir + Pact Broker property forwarding (ADR-0092/ADR-0250 Phase 2, issue #4414).
    // Was hand-copied, with real per-service drift, into 36 build.gradle.kts files — a rolled-up
    // hash check of those blocks found 5 distinct shapes (not 1), each diffed individually before
    // this centralisation: a `maxHeapSize` override (account/lending/product-catalog), a JUnit
    // timeout + CI-only jvmArgs (swift), presence/absence of the rootDir line depending on whether
    // the service only PROVIDES or only CONSUMES pacts (finrep/copilot vs clearing-simulator/
    // tpp-registry-service), and a `tasks.test { }`-scoped copy (party-service) instead of
    // `tasks.withType<Test>`. None of those differences is expressed here — they stay in each
    // service's own build.gradle.kts — this block only carries the part that was byte-identical
    // (or a harmless no-op superset) everywhere it appeared.
    //
    // Set on the test JVM fork, not the Gradle daemon — System.setProperty at config time would
    // not propagate into the forked test process.
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")
    listOf(
        "pactbroker.url",
        "pactbroker.auth.username",
        "pactbroker.auth.password",
        "pactbroker.enablePending",
        "pactbroker.providerBranch",
        "pact.verifier.publishResults",
        "pact.provider.version",
        "pact.provider.branch",
        "pact.provider.tag",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// ADR-0250 Phase 2 (issue #4414): a Pact provider-verification test needs `-Dpactbroker.url` set
// to run against the broker, and that `-D` is a tracked INPUT of whatever Test task runs it — so
// invoking the ordinary `test` task with a broker URL set (the main-push "contract" build) is
// ALWAYS a different Gradle cache key from invoking it without one (the "build" job on every
// other event), even when `--tests` filters which classes execute. That forces a full test-suite
// re-run on every main push. `--tests` was never going to fix this: it changes what runs, not
// what the task's inputs are.
//
// The fix is a SEPARATE Test task/source set so provider verification has its own task identity
// and its own cache key, decoupled from `test`'s. It deliberately reuses `test`'s own source
// directory (`src/test/kotlin`) rather than requiring every service to physically move its
// `*ProviderVerificationTest.kt` files into a new `src/providerPactTest/kotlin` tree — issue #4414
// finding 1 established that EVERY provider-verification class in the fleet already ends in
// `ProviderVerificationTest`, so an include filter on the existing directory selects exactly the
// right classes with zero fleet-wide file moves. Classes outside that filter (test helpers like a
// `*TestResource`, referenced by the provider-verification class via `@QuarkusTestResource`) are
// pulled in via the `test` source set's COMPILED output on the classpath, not recompiled — so
// running `providerPactTest` triggers `compileTestKotlin` (a compile task, whose inputs are just
// source files and is therefore cache-stable regardless of `-Dpactbroker.url`) but never EXECUTES
// the rest of `test`'s suite.
val providerPactTestSourceSet =
    sourceSets.create("providerPactTest") {
        kotlin.srcDir("src/test/kotlin")
        kotlin.include("**/*ProviderVerificationTest.kt")
        resources.srcDir("src/test/resources")
        compileClasspath += sourceSets.main.get().output +
            sourceSets.test.get().output +
            configurations.testCompileClasspath.get()
        runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
    }

val providerPactTest =
    tasks.register<Test>("providerPactTest") {
        description = "Runs Pact provider-verification tests only, isolated from `test` " +
            "(ADR-0250 Phase 2, issue #4414) — invoke with -Dpactbroker.url=... to verify " +
            "against the broker."
        group = org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = providerPactTestSourceSet.output.classesDirs
        classpath = providerPactTestSourceSet.runtimeClasspath
        // Independent of `check`/`test` — a service with no provider-verification classes at all
        // (the include filter above matches nothing) still gets the task registered, and it
        // reports 0 tests rather than failing; JUnit5's default `failOnNoTests` behaviour on an
        // EMPTY discovered set is "pass", not "fail", which is exactly what an unfiltered `test`
        // invocation of the same module already relies on for services with no tests of a kind.
        shouldRunAfter(tasks.named("test"))
    }
// Deliberately NOT wired into `check`/`build` (unlike koverVerify below): the whole point of
// this task is that a main-push "build" job (:service:build, which depends on `check`) must be
// able to run WITHOUT it, so it never becomes an input the hosted "build" job pays for. Invoke it
// explicitly: `./gradlew :service:providerPactTest -Dpactbroker.url=...`.

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSkipConfigs(listOf("testCompileClasspath", "testRuntimeClasspath", "annotationProcessor", "kapt"))
    setProjectType("application")
    setSchemaVersion("1.5")
}

// Kover instruments every class that a Quarkus test JVM loads unless told otherwise.
// Testcontainers is third-party test infrastructure, never part of this module's coverage
// denominator; attempting to transform its shaded classes has produced invalid frames and a
// missing XML report while the advisory CI step still looked green.  Exclude it at the
// instrumentation boundary (rather than report filtering) so application classes remain
// measured and a Testcontainers-heavy integration suite can still publish its evidence.
kover {
    currentProject {
        instrumentation {
            excludedClasses.add("org.testcontainers.*")
        }
    }
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
