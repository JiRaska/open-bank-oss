// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.cyclonedx.gradle.CycloneDxTask
import org.cyclonedx.gradle.GradleAssistedMavenModelResolverImpl
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

plugins {
    id("org.cyclonedx.bom")
}

val bomProject = project

tasks.withType<CycloneDxTask>().configureEach {
    doFirst {
        val runtime = bomProject.configurations.findByName("runtimeClasspath")
        if (runtime == null || !runtime.isCanBeResolved) {
            throw GradleException("POM preflight requires resolvable runtimeClasspath for ${bomProject.path}")
        }
        val resolution = runtime.incoming.resolutionResult
        resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>().forEach { throw it.failure }
        // Project components have no repository POM; their external dependencies remain in allComponents.
        val ids = resolution.allComponents.map { it.id }.filterIsInstance<ModuleComponentIdentifier>().toSet()
        bomProject.logger.lifecycle("POM_PREFLIGHT external_components=${ids.size} project=${bomProject.path}")
        val result = bomProject.dependencies.createArtifactResolutionQuery()
            .forComponents(ids).withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        if (result.components.map { it.id }.toSet() != ids) {
            throw GradleException("POM preflight did not return every requested component for ${bomProject.path}")
        }
        var resolvedModels = 0
        result.components.forEach { component ->
            if (component is UnresolvedComponentResult) throw component.failure
            if (component !is ComponentArtifactsResult) {
                throw GradleException("POM preflight returned an unexpected result for ${component.id}")
            }
            val artifact = component.getArtifacts(MavenPomArtifact::class.java).singleOrNull()
                ?: throw GradleException("POM preflight requires one POM for ${component.id}")
            if (artifact is UnresolvedArtifactResult) throw artifact.failure
            if (artifact !is ResolvedArtifactResult || !artifact.file.isFile) {
                throw GradleException("POM preflight resolved no file for ${component.id}")
            }
            // Use the pinned CycloneDX resolver and Maven's effective-model builder before the
            // plugin's catch-and-continue boundary. Maven owns parent/import/property resolution.
            val request = DefaultModelBuildingRequest()
                .setModelResolver(GradleAssistedMavenModelResolverImpl(bomProject))
                .setPomFile(artifact.file)
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
            request.systemProperties.putAll(System.getProperties())
            try {
                requireNotNull(DefaultModelBuilderFactory().newInstance().build(request).effectiveModel) {
                    "Maven returned no effective model for ${component.id}"
                }
            } catch (failure: Exception) {
                throw GradleException("Effective POM preflight failed for ${component.id}", failure)
            }
            resolvedModels++
        }
        bomProject.logger.lifecycle("POM_PREFLIGHT resolved_models=$resolvedModels project=${bomProject.path}")
    }
}
