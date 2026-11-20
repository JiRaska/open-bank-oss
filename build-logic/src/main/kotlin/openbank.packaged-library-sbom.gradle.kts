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

// CycloneDX 3.x resolves artifact files through an EXTERNAL-only artifact view
// (DependencyUtils.configureExternalArtifactView: componentFilter = ModuleComponentIdentifier),
// so in-build project dependencies (:openbank-libs-*) are listed without any hashes. 2.x hashed
// them. Restore that here from the same resolved runtimeClasspath artifact bytes, using the
// algorithm set the plugin emits for external components. Inventory is never added or removed:
// only an existing component whose purl project_path names a resolved project artifact gains
// hashes, so verifyPackagedLibrarySbom still fails on a library missing from the SBOM.
val sbomHashAlgorithms = linkedMapOf(
    "MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512",
    "SHA3-256" to "SHA3-256", "SHA3-512" to "SHA3-512", "SHA-384" to "SHA-384", "SHA3-384" to "SHA3-384",
)
val sbomRuntimeArtifacts = configurations.named("runtimeClasspath")
val projectArtifactFiles = providers.provider {
    sbomRuntimeArtifacts.get().incoming.artifacts.artifacts.mapNotNull { artifact ->
        val component = artifact.id.componentIdentifier as? ProjectComponentIdentifier
        component?.let { it.projectPath to artifact.file }
    }.groupBy({ it.first }, { it.second })
}

// Existing image/release consumers use reports/bom.{json,xml}. Export only those
// exact files; a Copy task targeting reports/ would reintroduce directory ownership.
val exportCycloneDxBom = tasks.register("exportCycloneDxBom") {
    dependsOn(cyclonedxBomTask)
    inputs.files(cyclonedxBomTask.map { listOfNotNull(it.jsonOutput.orNull, it.xmlOutput.orNull) })
        .withPropertyName("sbomFiles")
    inputs.files(sbomRuntimeArtifacts.map { cfg ->
        cfg.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier } }.files
    }).withPropertyName("projectRuntimeArtifacts")
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
                if (format == "json") {
                    target.writeText(withProjectLibraryHashes(file.readText(), projectArtifactFiles.get()))
                } else {
                    file.copyTo(target, overwrite = true)
                }
            } else {
                target.delete()
            }
        }
    }
}
cyclonedxBomTask.configure { finalizedBy(exportCycloneDxBom) }

fun withProjectLibraryHashes(json: String, artifacts: Map<String, List<File>>): String {
    @Suppress("UNCHECKED_CAST")
    val bom = groovy.json.JsonSlurper().parseText(json) as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val components = (bom["components"] as? List<MutableMap<String, Any?>>).orEmpty()
    for (component in components) {
        val purl = component["purl"] as? String ?: continue
        val query = purl.substringAfter('?', "")
        val projectPath = query.split('&').firstOrNull { it.startsWith("project_path=") }
            ?.let { java.net.URLDecoder.decode(it.removePrefix("project_path="), Charsets.UTF_8) } ?: continue
        val files = artifacts[projectPath] ?: continue
        check(files.size == 1) { "Project $projectPath resolved to ${files.size} runtime artifacts; cannot hash the SBOM component" }
        check(component["hashes"] == null) {
            "CycloneDX already hashed project component $projectPath; drop withProjectLibraryHashes"
        }
        val bytes = files.single().readBytes()
        component["hashes"] = sbomHashAlgorithms.map { (alg, jca) ->
            linkedMapOf("alg" to alg, "content" to java.security.MessageDigest.getInstance(jca).digest(bytes)
                .joinToString("") { "%02x".format(it) })
        }
    }
    return JsonOutput.prettyPrint(JsonOutput.toJson(bom))
}

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
