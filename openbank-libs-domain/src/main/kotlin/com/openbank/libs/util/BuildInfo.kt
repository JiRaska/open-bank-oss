// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import java.util.Properties

/**
 * Static tech-stack snapshot for the running service.
 *
 * Build-time data (Kotlin / Quarkus / Gradle versions, build time, git commit)
 * comes from `openbank-build-info.properties` which Gradle stamps from
 * `libs.versions.toml` at processResources time. Runtime data (JVM version,
 * vendor) comes from `Runtime.version()` / system properties at class init.
 *
 * Loaded **once** per JVM at class init via the object initializer — no
 * per-request overhead. Exposed via `com.openbank.libs.web.ServiceInfoResource`
 * `/api/v1/info` so admin UI can surface the stack inventory.
 */
object BuildInfo {

    private val props: Properties = run {
        val p = Properties()
        BuildInfo::class.java.classLoader
            .getResourceAsStream("openbank-build-info.properties")
            ?.use { p.load(it) }
        p
    }

    private fun prop(key: String, default: String = "unknown"): String =
        props.getProperty(key)?.takeIf { it.isNotBlank() && !it.startsWith("@") } ?: default

    /** Kotlin compiler version this JAR was built with. */
    val kotlinVersion: String = prop("kotlin.version", KotlinVersion.CURRENT.toString())

    /** Quarkus platform version this JAR was built against. */
    val quarkusVersion: String = prop("quarkus.version")

    /** Whether the bundled Quarkus is an LTS line. */
    val quarkusLts: Boolean = prop("quarkus.lts", "false").toBoolean()

    /** ISO-8601 date the LTS support window closes (`unknown` if not LTS). */
    val quarkusSupportUntil: String = prop("quarkus.support.until")

    /** Gradle version used to build the JAR. */
    val gradleVersion: String = prop("gradle.version")

    /** Build time in `YYYY-MM-DDTHH:MM:SSZ` (UTC). */
    val buildTime: String = prop("build.time")

    /** Short git commit (HEAD at build time). */
    val gitCommit: String = prop("git.commit")

    /** Version of the openbank-libs JAR itself. */
    val libsVersion: String = prop("libs.version")

    /** Currently running JVM version, e.g. `25.0.1`. */
    val javaVersion: String = Runtime.version().toString()

    /** JVM vendor, e.g. `Eclipse Adoptium`. */
    val javaVendor: String = System.getProperty("java.vendor") ?: "unknown"

    /** OS architecture, e.g. `aarch64`. */
    val osArch: String = System.getProperty("os.arch") ?: "unknown"

    /** Available processors visible to this JVM. */
    val cpuCount: Int = Runtime.getRuntime().availableProcessors()

    /** Configured max heap in MiB, useful for visualising container size at a glance. */
    val maxHeapMib: Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    /**
     * Stable JSON-friendly map for the `/api/v1/info` response. Order is intentional:
     * what a human looks for first (kotlin, quarkus, java) goes on top.
     */
    fun toStack(): Map<String, Any> = linkedMapOf(
        "kotlin" to mapOf("version" to kotlinVersion),
        "quarkus" to mapOf(
            "version" to quarkusVersion,
            "lts" to quarkusLts,
            "supportUntil" to quarkusSupportUntil,
        ),
        "java" to mapOf(
            "version" to javaVersion,
            "vendor" to javaVendor,
            "arch" to osArch,
            "cpu" to cpuCount,
            "maxHeapMib" to maxHeapMib,
        ),
        "gradle" to mapOf("version" to gradleVersion),
        "libs" to mapOf(
            "version" to libsVersion,
            "buildTime" to buildTime,
            "gitCommit" to gitCommit,
        ),
    )
}
