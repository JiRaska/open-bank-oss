// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

pluginManagement {
    repositories {
        // In-cluster Reposilite mirror (issue #849): ARC runners probe the ClusterIP
        // at build start; if reachable they export REPOSILITE_MIRROR_URL and Gradle
        // resolves deps from inside the cluster (zero NAT). Hetzner/Mac runners get
        // ECONNREFUSED on the probe, env var stays unset, and fall through to GCS.
        val reposiliteUrl = System.getenv("REPOSILITE_MIRROR_URL")?.takeIf { it.isNotBlank() }
        if (reposiliteUrl != null) {
            maven("$reposiliteUrl/maven-central/")
            maven("$reposiliteUrl/gradle-plugins/")
        }
        // GCS mirror as second-level fallback (original #849 fix — survives Reposilite restart).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        val reposiliteUrl = System.getenv("REPOSILITE_MIRROR_URL")?.takeIf { it.isNotBlank() }
        if (reposiliteUrl != null) {
            maven("$reposiliteUrl/maven-central/")
            maven("$reposiliteUrl/gradle-plugins/")
            maven("$reposiliteUrl/google/")
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
    // Share version catalog with the root build — reads the TOML directly so
    // there is a single source of truth for plugin versions (ADR-0029 D1).
    versionCatalogs {
        create("libs") { from(files("../openbank-libs/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
