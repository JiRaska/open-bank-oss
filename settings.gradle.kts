// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import org.gradle.caching.http.HttpBuildCache

pluginManagement {
    repositories {
        // In-cluster Reposilite mirror (issue #849): ARC runners probe the ClusterIP
        // at build start and export REPOSILITE_MIRROR_URL when reachable. Resolves
        // plugin deps from inside the cluster — zero NAT for ARC runners. Hetzner/Mac
        // runners leave the var unset and fall through to the GCS mirror below.
        val reposiliteUrl = System.getenv("REPOSILITE_MIRROR_URL")?.takeIf { it.isNotBlank() }
        if (reposiliteUrl != null) {
            maven("$reposiliteUrl/maven-central/")
            maven("$reposiliteUrl/gradle-plugins/")
        }
        // GCS mirror as second-level fallback (original #849 fix).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

// Convention plugins for every openbank-*-service (ADR-0029 D1).
// Must appear before `plugins {}` so Gradle registers the composite build
// before it tries to resolve the `id("openbank.quarkus-service")` plugin id.
includeBuild("build-logic")

rootProject.name = "openbank"

plugins {
    // Auto-downloads a matching JDK when none of the locally installed ones satisfies
    // `kotlin { jvmToolchain(N) }`. Required because Kotlin 2.0.21 does not support
    // Java 24+ and many dev workstations now default to JDK 24/25/26.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Shared remote build cache (ADR-0043). Opt-in via env so local dev and any
// un-provisioned runner fall back to the local cache with zero config. CI runners
// set GRADLE_REMOTE_CACHE_URL to a self-hosted `gradle/build-cache-node` and
// GRADLE_REMOTE_CACHE_PUSH=true, so every job across the whole pool reads+writes one
// cache — a fleet-wide fan-out then resolves unchanged modules from cache instead of
// recompiling them on each of the two Mac hosts independently.
val remoteCacheUrl: String? = System.getenv("GRADLE_REMOTE_CACHE_URL")
if (!remoteCacheUrl.isNullOrBlank()) {
    buildCache {
        local { isEnabled = true }
        remote(HttpBuildCache::class) {
            url = uri(remoteCacheUrl)
            isPush = System.getenv("GRADLE_REMOTE_CACHE_PUSH")?.toBoolean() ?: false
            // node may run plain HTTP inside the isolated runner network
            isAllowInsecureProtocol = System.getenv("GRADLE_REMOTE_CACHE_INSECURE")?.toBoolean() ?: false
            val user = System.getenv("GRADLE_REMOTE_CACHE_USER")
            val pass = System.getenv("GRADLE_REMOTE_CACHE_PASSWORD")
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                credentials {
                    username = user
                    password = pass
                }
            }
        }
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("openbank-libs/gradle/libs.versions.toml"))
        }
    }
}

// Include every Gradle module physically present in this checkout: `openbank-libs`
// plus each `openbank-<service>` directory that has a `build.gradle.kts`. In CI / a
// full clone that is the whole fleet; in a per-service container build — whose
// Dockerfile copies only `openbank-libs` and the one service — it is exactly that
// subset. A previous static `include(...)` list asked Gradle to configure projects
// whose directories were not copied, which failed every per-service image build with
// "Configuring project ':openbank-<x>' without an existing directory is not allowed".
// (`openbank-admin-ui` is Next.js and `openbank-infra` is OpenTofu — no build.gradle.kts,
// so neither is picked up.) Each module's projectDir defaults to its directory name,
// so no explicit `projectDir` wiring is needed.
rootDir.listFiles { f ->
    f.isDirectory && f.name.startsWith("openbank-") && f.resolve("build.gradle.kts").isFile
}.orEmpty()
    .map { it.name }
    .sorted()
    .forEach { include(":$it") }
