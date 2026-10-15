// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

// Shared policy pre-flight gate for OpenBank AI agents (ADR-0031 D9 phase 4).
//
// WHY THIS IS ITS OWN MODULE AND NOT PART OF openbank-flaky-test-hunter
// Phase 3 built the two deny-by-default policy layers (in-process charter re-check + OPA
// sidecar decision) inside flaky-test-hunter, hardcoded to its agent id, its gh.pr.open tool
// and its github-pr resource. Phase 4 generalises the same gate for every future agent write
// path — the money-path repair agents first — so the (tier, resource) grant vocabulary, the
// fail-closed PDP contract and the charter parsing live here, parameterised by AgentPolicyConfig,
// and each service supplies its own CDI adapter (see flaky-test-hunter's PrPathPolicyGate).
//
// Plain kotlin-jvm on purpose: the module carries no framework annotations, so any service —
// Quarkus or not — can construct the gate. The consuming service owns the kill switch, the
// config mapping and the audit publishing; this module only decides ALLOW/DENY.
//
// Licensed AGPL-3.0-only (ADR-0136 boundary, rules.yaml: dependencies.license_boundary_exceptions):
// only AGPL-3.0-only services may depend on this module; Apache-2.0 platform modules must not.
// Depending on openbank-libs-runtime (Apache-2.0) for BoundedBodyHandlers is permitted —
// copyleft may consume permissive code, never the reverse.

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // GCS mirror of Maven Central first (#849) — shared NAT egress IP gets
    // 429-throttled by Central during fleet-wide build storms; 404 falls through.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    // Charter YAML + OPA decision JSON. Literal versions, matching the fleet pin in
    // openbank-libs/gradle/libs.versions.toml (jackson = "2.22.3") — this module applies
    // no Quarkus platform BOM, so the catalog coordinates are resolved as-is and already
    // sit in gradle/verification-metadata.xml.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")

    // BoundedBodyHandlers (ADR-0320 P1): the size-capped response handler for the OPA
    // sidecar call. A project dependency, not a copy — the fleet control stays single-source.
    implementation(project(":openbank-libs-runtime"))

    // jboss-logging, compileOnly, matching openbank-libs-runtime's convention: consuming
    // Quarkus services provide the backend via the platform.
    compileOnly("org.jboss.logging:jboss-logging:3.6.2.Final")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation("org.jboss.logging:jboss-logging:3.6.2.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Package the agent charters so the in-process charter verifier (AgentCharterGrantVerifier)
// can re-verify the served agents.yaml grant in-process, deny-by-default, without a network
// call. Derived from openbank-libs/governance/agents.yaml — never hand-edit the packaged copy.
// (Moved here from openbank-flaky-test-hunter with the phase-4 extraction: the verifier's
// classloader is this module's now, so the resource must live on THIS classpath.)
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance")) {
        include("agents.yaml")
        into("governance-charters")
    }
}

tasks.test {
    useJUnitPlatform()
    // Ephemeral Quarkus test ports — this module is not a Quarkus service, but the
    // quarkus-test-ports-ephemeral gate enforces the properties fleet-wide; openbank-libs-temporal
    // carries the same comment and the same three lines.
    systemProperty("quarkus.http.test-port", "0")
    systemProperty("quarkus.http.test-ssl-port", "0")
    systemProperty("quarkus.management.test-port", "0")
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}

kover {
    reports {
        verify {
            rule {
                // Ratchet floor: measured 99.1% LINE coverage (2026-10-15, kover report,
                // 112/113 lines). The single uncovered line is a defensive Jackson-failure
                // branch in OpaAgentDecisionClient.parse that the loopback stub cannot
                // distinguish from the branches that do run. Raise-only from here.
                bound {
                    minValue = 97
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
