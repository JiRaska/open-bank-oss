// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
