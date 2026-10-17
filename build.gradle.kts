// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Include every CycloneDX owner, including projects outside the Quarkus convention.
subprojects {
    val bomProject = this
    bomProject.pluginManager.withPlugin("org.cyclonedx.bom") {
        bomProject.pluginManager.apply("openbank.cyclonedx-verification")
    }
}

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
