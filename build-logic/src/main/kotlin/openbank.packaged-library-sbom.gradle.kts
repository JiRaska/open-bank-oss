// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import groovy.json.JsonOutput
import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// CycloneDX 3.x splits resolution (cyclonedxDirectBom, per project) from the published
// SBOM (cyclonedxBom, aggregating this project and its subprojects). Both declare
// per-file outputs; keep them under their own directory and export the exact files.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    skipConfigs.set(listOf("testCompileClasspath", "testRuntimeClasspath", "annotationProcessor", "kapt"))
    projectType.set(Component.Type.APPLICATION)
    schemaVersion.set(Version.VERSION_15)
}
val cyclonedxBomTask = tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    projectType.set(Component.Type.APPLICATION)
    schemaVersion.set(Version.VERSION_15)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.xml"))
}

// Existing image/release consumers use reports/bom.{json,xml}. Export only those
// exact files; a Copy task targeting reports/ would reintroduce directory ownership.
val exportCycloneDxBom = tasks.register("exportCycloneDxBom") {
    dependsOn(cyclonedxBomTask)
    inputs.files(cyclonedxBomTask.map { listOfNotNull(it.jsonOutput.orNull, it.xmlOutput.orNull) })
        .withPropertyName("sbomFiles")
    outputs.files(layout.buildDirectory.file("reports/bom.json"), layout.buildDirectory.file("reports/bom.xml"))
    onlyIf {
        val producer = cyclonedxBomTask.get()
        producer.enabled && runCatching { producer.state.rethrowFailure() }.isSuccess &&
            (producer.state.didWork || producer.state.upToDate)
    }
    doLast {
        val producer = cyclonedxBomTask.get()
        val sources = mapOf("json" to producer.jsonOutput.orNull, "xml" to producer.xmlOutput.orNull)
        for ((format, source) in sources) {
            val target = layout.buildDirectory.file("reports/bom.$format").get().asFile
            if (source != null) {
                val file = source.asFile
                check(file.isFile) { "CycloneDX did not produce the enabled $format SBOM" }
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            } else {
                target.delete()
            }
        }
    }
}
cyclonedxBomTask.configure { finalizedBy(exportCycloneDxBom) }

// Explicit deploy-build proof. Never attach to sbomAll: resolution-only inventory
// must not assemble the fleet. This receipt covers original dependency JARs only;
// Quarkus augmentation and image/deployment provenance remain separate evidence.
val runtimeArtifacts = configurations.named("runtimeClasspath")
val packagedApp = layout.buildDirectory.dir("quarkus-app")
val operationalSbom = layout.buildDirectory.file("reports/bom.json")
val verifier = rootProject.layout.projectDirectory.file(".github/scripts/verify-packaged-library-sbom.py")
val proof = layout.buildDirectory.file("reports/library-artifacts/verification.json")

// Ordering does not add assembly to standalone CycloneDX inventory requests.
tasks.named("cyclonedxBom") { mustRunAfter("quarkusBuild") }
val projectArtifactIdentities = providers.provider {
    val incoming = runtimeArtifacts.get().incoming
    val coordinates = incoming.resolutionResult.allComponents.associate { it.id to it.moduleVersion }
    incoming.artifacts.artifacts.mapNotNull { artifact ->
        val component = artifact.id.componentIdentifier as? ProjectComponentIdentifier
        component?.let {
            val coordinate = checkNotNull(coordinates[it]) { "Resolved project artifact has no module coordinates: $it" }
            "${it.build.buildPath}:${it.projectPath}:$coordinate:${artifact.id.displayName}"
        }
    }.sorted()
}

tasks.register("verifyPackagedLibrarySbom") {
    group = "verification"
    description = "Verify CycloneDX original library hashes against resolved and packaged JAR bytes."
    dependsOn("quarkusBuild", "cyclonedxBom", "exportCycloneDxBom")
    inputs.property("resolvedProjectArtifactIdentities", projectArtifactIdentities)
    inputs.files(runtimeArtifacts).withPropertyName("resolvedRuntimeArtifacts")
    inputs.file(operationalSbom).withPropertyName("operationalSbom")
    inputs.dir(packagedApp).withPropertyName("augmentedPackage")
    inputs.file(verifier).withPropertyName("verifier")
    outputs.file(proof)
    doLast {
        val receipt = proof.get().asFile
        // Discard the prior receipt before executing verification. Earlier Gradle input
        // resolution failures stop the required producer task before image staging.
        receipt.delete()
        val artifacts = runtimeArtifacts.get().incoming.artifacts.artifacts
            .mapNotNull { artifact ->
                val component = artifact.id.componentIdentifier as? ProjectComponentIdentifier
                component?.let { mapOf("projectPath" to it.projectPath, "file" to artifact.file.canonicalPath) }
            }.sortedBy { it.getValue("projectPath") }
        val resolved = temporaryDir.resolve("resolved-runtime-projects.json")
        resolved.writeText(JsonOutput.toJson(artifacts))
        val output = providers.exec {
            commandLine("python3", verifier.asFile.absolutePath,
                "--sbom", operationalSbom.get().asFile.absolutePath,
                "--runtime-artifacts", resolved.absolutePath,
                "--quarkus-app", packagedApp.get().asFile.absolutePath)
        }.standardOutput.asText.get()
        receipt.parentFile.mkdirs()
        receipt.writeText(output)
    }
}
