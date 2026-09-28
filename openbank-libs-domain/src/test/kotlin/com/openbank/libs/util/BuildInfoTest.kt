// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `openbank-build-info.properties` is stamped by Gradle's `processResources` in a service module
 * (from `libs.versions.toml`); `openbank-libs-domain` itself never generates it, so under this
 * module's own test classpath the resource stream is absent and every build-time property falls
 * back to its documented default. That fallback path — never letting a missing resource surface as
 * an empty string or a thrown exception — is exactly what these assertions pin.
 */
class BuildInfoTest {

    @Test
    fun `a build-time property falls back to unknown when the stamped resource is absent`() {
        assertThat(BuildInfo.quarkusVersion).isEqualTo("unknown")
        assertThat(BuildInfo.gradleVersion).isEqualTo("unknown")
        assertThat(BuildInfo.buildTime).isEqualTo("unknown")
        assertThat(BuildInfo.gitCommit).isEqualTo("unknown")
        assertThat(BuildInfo.libsVersion).isEqualTo("unknown")
    }

    @Test
    fun `kotlinVersion falls back to the running Kotlin compiler version, not a blank string`() {
        assertThat(BuildInfo.kotlinVersion).isEqualTo(KotlinVersion.CURRENT.toString())
        assertThat(BuildInfo.kotlinVersion).isNotBlank()
    }

    @Test
    fun `quarkusLts defaults to false rather than throwing on a missing flag`() {
        assertThat(BuildInfo.quarkusLts).isFalse()
    }

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
