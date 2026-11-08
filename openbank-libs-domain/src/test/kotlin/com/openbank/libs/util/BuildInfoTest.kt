// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `openbank-build-info.properties` is stamped by THIS module's processResources (it used to live in
 * the umbrella openbank-libs JAR, which services depending on domain/runtime directly never had, so
 * admin-ui showed Quarkus/Gradle/libs as `unknown`). The expected values are handed in by Gradle
 * from the version catalog, so an unstamped or missing resource fails here.
 * `openbank-service-build.properties` is stamped by the service convention, never by this module,
 * so the commit still falls back to its documented default.
 */
class BuildInfoTest {

    @Test
    fun `build-time stack versions are stamped from the version catalog, never unknown`() {
        assertThat(BuildInfo.quarkusVersion).isEqualTo(expected("expected.quarkus.version"))
        assertThat(BuildInfo.kotlinVersion).isEqualTo(expected("expected.kotlin.version"))
        assertThat(BuildInfo.gradleVersion).isEqualTo(expected("expected.gradle.version"))
        assertThat(BuildInfo.libsVersion).isEqualTo(expected("expected.libs.version"))
        assertThat(BuildInfo.quarkusLts).isTrue()
    }

    @Test
    fun `values never stamped by this module fall back to unknown, not a blank or a token`() {
        assertThat(BuildInfo.buildTime).isEqualTo("unknown")
        assertThat(BuildInfo.gitCommit).isEqualTo("unknown")
    }

    private fun expected(key: String): String =
        requireNotNull(System.getProperty(key)) { "Gradle must pass -D$key (see build.gradle.kts)" }

    @Test
    fun `runtime JVM properties reflect the actual running JVM, not the stamped-resource fallback`() {
        assertThat(BuildInfo.javaVersion).isEqualTo(Runtime.version().toString())
        assertThat(BuildInfo.javaVendor).isEqualTo(System.getProperty("java.vendor") ?: "unknown")
        assertThat(BuildInfo.osArch).isEqualTo(System.getProperty("os.arch") ?: "unknown")
        assertThat(BuildInfo.cpuCount).isEqualTo(Runtime.getRuntime().availableProcessors())
        assertThat(BuildInfo.cpuCount).isGreaterThan(0)
        assertThat(BuildInfo.maxHeapMib).isGreaterThan(0)
    }

    @Test
    fun `toStack renders a JSON-friendly map with kotlin, quarkus, java, gradle and libs top-level keys`() {
        val stack = BuildInfo.toStack()

        assertThat(stack.keys).containsExactly("kotlin", "quarkus", "java", "gradle", "libs")

        @Suppress("UNCHECKED_CAST")
        val quarkus = stack["quarkus"] as Map<String, Any>
        assertThat(quarkus["version"]).isEqualTo(BuildInfo.quarkusVersion)
        assertThat(quarkus["lts"]).isEqualTo(BuildInfo.quarkusLts)
        assertThat(quarkus["supportUntil"]).isEqualTo(BuildInfo.quarkusSupportUntil)

        @Suppress("UNCHECKED_CAST")
        val java = stack["java"] as Map<String, Any>
        assertThat(java["version"]).isEqualTo(BuildInfo.javaVersion)
        assertThat(java["arch"]).isEqualTo(BuildInfo.osArch)
        assertThat(java["cpu"]).isEqualTo(BuildInfo.cpuCount)

        @Suppress("UNCHECKED_CAST")
        val libs = stack["libs"] as Map<String, Any>
        assertThat(libs["version"]).isEqualTo(BuildInfo.libsVersion)
        assertThat(libs["gitCommit"]).isEqualTo(BuildInfo.gitCommit)
    }

    @Test
    fun `toStack lists kotlin before quarkus before java, the order a human looks for first`() {
        assertThat(BuildInfo.toStack().keys.toList()).containsExactly("kotlin", "quarkus", "java", "gradle", "libs")
    }
}
