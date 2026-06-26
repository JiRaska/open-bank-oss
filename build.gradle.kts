// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Root build file — empty by design. Per-service build.gradle.kts files own
// their own plugin set and configure cyclonedxBom in-place because the
// CycloneDxTask type is on the per-project plugin classpath, not the root.

// Aggregate convenience: `./gradlew sbomAll` runs cyclonedxBom on every
// subproject that has the org.cyclonedx.bom plugin. CI uses this instead of
// listing all 28 services explicitly. The list is resolved at configuration
// time so an added service shows up automatically once its build.gradle.kts
// applies the cyclonedx plugin.
tasks.register("sbomAll") {
    group = "verification"
    description = "Generate a CycloneDX SBOM (build/reports/bom.json) for every Quarkus service."
    dependsOn(
        subprojects.mapNotNull { sub ->
            sub.tasks.findByName("cyclonedxBom")?.let { "${sub.path}:cyclonedxBom" }
        }
    )
}
