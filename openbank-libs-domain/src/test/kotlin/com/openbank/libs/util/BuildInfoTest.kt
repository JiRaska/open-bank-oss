// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [BuildInfo] is read once at class init and served over `/api/v1/info`. The failure mode worth
 * testing is the one that renders as a plausible page: an unresolved Gradle token (`@kotlin.version@`)
 * or a blank property leaking through as if it were a real version, instead of the honest `unknown`.
 */
class BuildInfoTest {

    @Test
    fun `no field ever surfaces an unresolved gradle placeholder or a blank`() {
        val strings = listOf(
            BuildInfo.kotlinVersion,
            BuildInfo.quarkusVersion,
            BuildInfo.quarkusSupportUntil,
            BuildInfo.gradleVersion,
            BuildInfo.buildTime,
            BuildInfo.gitCommit,
            BuildInfo.libsVersion,
            BuildInfo.javaVersion,
            BuildInfo.javaVendor,
            BuildInfo.osArch,
        )
        assertThat(strings).allSatisfy {
            assertThat(it).isNotBlank()
            assertThat(it).doesNotStartWith("@")
        }
    }

    @Test
    fun `kotlin version falls back to the compiler's own version rather than unknown`() {
        // The fallback is KotlinVersion.CURRENT, so this is never the generic "unknown" sentinel
        // even when the stamped properties file is missing from the test runtime classpath.
        assertThat(BuildInfo.kotlinVersion).isNotEqualTo("unknown")
        assertThat(BuildInfo.kotlinVersion).matches("^\\d+\\.\\d+.*")
    }

    @Test
    fun `runtime facts are read from the live JVM, not from the stamped properties`() {
        assertThat(BuildInfo.javaVersion).isEqualTo(Runtime.version().toString())
        assertThat(BuildInfo.cpuCount).isEqualTo(Runtime.getRuntime().availableProcessors())
        assertThat(BuildInfo.cpuCount).isPositive()
        assertThat(BuildInfo.maxHeapMib).isPositive()
    }

    @Test
    fun `toStack exposes every group the info endpoint renders, in a stable order`() {
        val stack = BuildInfo.toStack()
        assertThat(stack.keys).containsExactly("kotlin", "quarkus", "java", "gradle", "libs")
    }

    @Test
    fun `toStack carries the same values as the individual accessors`() {
        val stack = BuildInfo.toStack()

        @Suppress("UNCHECKED_CAST")
        val java = stack["java"] as Map<String, Any>
        assertThat(java["version"]).isEqualTo(BuildInfo.javaVersion)
        assertThat(java["vendor"]).isEqualTo(BuildInfo.javaVendor)
        assertThat(java["arch"]).isEqualTo(BuildInfo.osArch)
        assertThat(java["cpu"]).isEqualTo(BuildInfo.cpuCount)
        assertThat(java["maxHeapMib"]).isEqualTo(BuildInfo.maxHeapMib)

        @Suppress("UNCHECKED_CAST")
        val quarkus = stack["quarkus"] as Map<String, Any>
        assertThat(quarkus["version"]).isEqualTo(BuildInfo.quarkusVersion)
        assertThat(quarkus["lts"]).isEqualTo(BuildInfo.quarkusLts)
        assertThat(quarkus["supportUntil"]).isEqualTo(BuildInfo.quarkusSupportUntil)
    }

    @Test
    fun `toStack is a fresh map each call so a caller cannot mutate the shared snapshot`() {
        val a = BuildInfo.toStack()
        val b = BuildInfo.toStack()
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotSameAs(b)
    }
}
