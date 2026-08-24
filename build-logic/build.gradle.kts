// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// WHY we read versions from the TOML directly instead of using the typed `libs.*`
// catalog accessor: the typed accessor is generated at the root composite level and
// is NOT available inside a build-logic composite build when Gradle bootstraps it
// (the catalog is resolved after the build-logic plugin itself has compiled).
// Reading the raw TOML text is the established pattern in this repo — see
// openbank-libs/build.gradle.kts lines 107-116 for the exact same approach.
val tomlContent: String = providers.fileContents(
    layout.projectDirectory.file("../openbank-libs/gradle/libs.versions.toml")
).asText.get()

// Parse [versions] section (stops at first [libraries] or [plugins] header)
val versionsMap: Map<String, String> = tomlContent.lineSequence()
    .takeWhile { !it.startsWith("[libraries]") && !it.startsWith("[plugins]") }
    .mapNotNull { line ->
        Regex("""^(\w[\w.-]*)\s*=\s*"([^"]+)"\s*$""").find(line.trim())?.let {
            it.groupValues[1] to it.groupValues[2]
        }
    }
    .toMap()

// cyclonedx version is declared inline in the [plugins] section:
//   cyclonedx = { id = "org.cyclonedx.bom", version = "2.3.0" }
// so it is NOT in [versions]. Parse it from the plugins table directly.
val cyclonedxVersion: String = tomlContent.lineSequence()
    .dropWhile { !it.startsWith("[plugins]") }
    .firstOrNull { it.contains("cyclonedx") }
    ?.let { Regex("""version\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
    ?: error("cyclonedx plugin version missing in libs.versions.toml")

val kotlinVersion  = versionsMap["kotlin"]         ?: error("kotlin version missing in libs.versions.toml")
val quarkusVersion = versionsMap["quarkus-plugin"] ?: error("quarkus-plugin version missing in libs.versions.toml")
val koverVersion   = versionsMap["kover"]           ?: error("kover version missing in libs.versions.toml")
val ktlintPluginVersion = versionsMap["ktlint-plugin"] ?: error("ktlint-plugin version missing in libs.versions.toml")

plugins {
    `kotlin-dsl`
}

dependencies {
    // Declare the plugins that the convention plugin applies, so Gradle can
    // resolve them at build-logic compile time.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin.plugin.allopen:org.jetbrains.kotlin.plugin.allopen.gradle.plugin:$kotlinVersion")
    implementation("io.quarkus:io.quarkus.gradle.plugin:$quarkusVersion")
    implementation("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:$cyclonedxVersion")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:$koverVersion")
    // Static analysis (SAST gate), applied fleet-wide by the openbank.static-analysis
    // convention plugin. Only ktlint is a Gradle plugin here — detekt is invoked as a
    // forked CLI (see openbank.static-analysis.gradle.kts for why), so its version
    // lives in [versions] of the catalog, not on this classpath.
    implementation("org.jlleitschuh.gradle.ktlint:org.jlleitschuh.gradle.ktlint.gradle.plugin:$ktlintPluginVersion")
}

// Security floor (issue #461 follow-up): the Quarkus Gradle plugin pulls in maven-core ->
// plexus-utils 4.0.2 transitively (its embedded Maven resolver, used for devtools/
// registry-client), which carries a directory-traversal vulnerability in extractFile
// (GHSA-6fmv-xxpf-w3cw, CVE-2025-67030). gradle/osv-scanner.toml notes this couldn't be
// forced from a *service* project's resolutionStrategy — a service's configurations.all
// cannot reach into an already-applied Gradle plugin's own classpath. This project is
// different: build-logic declares io.quarkus:io.quarkus.gradle.plugin as a plain
// `implementation` dependency of *this* project (see above), so build-logic's own
// resolutionStrategy applies directly to it.
//
// jackson-databind/jackson-core: gradle/verification-metadata.xml still carries a real
// (jar-backed, not pom-only stub) pre-patch resolution alongside the fleet-wide 2.22.1
// floor from openbank.dependency-vulnerability-pins — every service and
// openbank-simulation already apply that plugin, so the lingering old jar is most likely
// the same class of gap as plexus-utils above: the Quarkus Gradle plugin's own
// devtools/registry-client tooling pulls Jackson for its own use, on a classpath a
// service's force() cannot reach. Floor it here too on the off chance that's the source;
// harmless no-op otherwise. Netty is the same story — the plugin's own
// quarkusBuild/quarkusDev augmentation classpath resolves its own Vert.x/Netty stack
// independently of any service's resolutionStrategy (Dependabot #20-#56 sweep still
// showed pre-4.1.135.Final jars in gradle/verification-metadata.xml after every service
// was floored).
configurations.all {
    resolutionStrategy {
        force("org.codehaus.plexus:plexus-utils:4.1.0")
        force("com.fasterxml.jackson.core:jackson-databind:2.22.2")
        force("com.fasterxml.jackson.core:jackson-core:2.22.2")
        force("io.netty:netty-codec:4.2.17.Final")
        force("io.netty:netty-codec-http:4.2.17.Final")
        force("io.netty:netty-codec-http2:4.2.17.Final")
        force("io.netty:netty-codec-haproxy:4.2.17.Final")
        force("io.netty:netty-common:4.2.17.Final")
        force("io.netty:netty-handler:4.2.17.Final")
        force("io.netty:netty-handler-proxy:4.2.17.Final")
        force("io.netty:netty-resolver-dns:4.2.17.Final")
        force("io.netty:netty-transport-native-epoll:4.2.17.Final")
        force("io.netty:netty-transport-native-kqueue:4.2.17.Final")
    }
}
