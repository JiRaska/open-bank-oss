// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import java.util.zip.ZipFile

// Every service publishes documentation from the same build that produces its JAR.
// The generated page contains only facts from build inputs. It also supplies an
// honest index for new services until they add authored docs to src/main/resources/docs.
val generatedServiceDocs = layout.buildDirectory.dir("generated/service-docs")
// The commit is an explicit INPUT, resolved in this order:
//   1. SOURCE_COMMIT - set by every container build (Docker build arg, see
//      standalone-service.Dockerfile); a container has no .git (.dockerignore drops it).
//   2. GITHUB_SHA    - set by GitHub Actions for host-side builds.
//   3. `git rev-parse HEAD` - a developer's checkout. Run with ignoreExitValue, so a tree
//      without .git yields an empty value and the task below fails with an actionable
//      message instead of "Error while evaluating property 'sourceCommit'".
// There is deliberately no placeholder hash: the value is published as a build fact.
val sourceCommit = providers.environmentVariable("SOURCE_COMMIT").map { it.trim() }.filter { it.isNotEmpty() }
    .orElse(providers.environmentVariable("GITHUB_SHA").map { it.trim() }.filter { it.isNotEmpty() })
    .orElse(
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() },
    )
val generateServiceDocs by tasks.registering {
    val versionFile = layout.projectDirectory.file("version.txt")
    val openapiFile = layout.projectDirectory.file("src/main/resources/openapi.yaml")
    val authoredDocs = fileTree("src/main/resources/docs") { include("*.md") }
    inputs.file(versionFile)
    inputs.property("sourceCommit", sourceCommit)
    inputs.files(authoredDocs)
    inputs.files(openapiFile.asFile.takeIf { it.exists() }?.let { files(it) } ?: files())
    outputs.dir(generatedServiceDocs)
    doLast {
        val docs = generatedServiceDocs.get().asFile.resolve("docs")
        docs.deleteRecursively()
        docs.mkdirs()
        val releaseVersion = versionFile.asFile.readText().trim()
        val gitCommit = sourceCommit.get().trim()
        check(Regex("[0-9a-fA-F]{40}").matches(gitCommit)) {
            "${project.name}: no source commit - set SOURCE_COMMIT (Docker: --build-arg SOURCE_COMMIT=<sha>), " +
                "GITHUB_SHA, or build from a git checkout; got '$gitCommit'"
        }
        generatedServiceDocs.get().asFile.resolve("openbank-service-build.properties")
            .writeText("git.commit=$gitCommit\n")
        val apiVersion = if (openapiFile.asFile.isFile) {
            val info = Regex("(?m)^info:\\s*$([\\s\\S]*?)(?=^\\S|\\z)")
                .find(openapiFile.asFile.readText())?.groupValues?.get(1).orEmpty()
            Regex("(?m)^\\s+version:\\s*['\"]?([^'\"\\s#]+)")
                .find(info)?.groupValues?.get(1)
        } else null
        val module = project.name
        docs.resolve("00-build.md").writeText(buildString {
            appendLine("# Build facts — $module")
            appendLine()
            appendLine("Generated from this service's build inputs. This page is packaged in the running service, not copied from the Admin UI.")
            appendLine()
            appendLine("| Source | Value |")
            appendLine("|---|---|")
            appendLine("| Module | `$module` |")
            appendLine("| Release version (`version.txt`) | `$releaseVersion` |")
            appendLine("| Source commit | `$gitCommit` |")
            appendLine("| API contract version (`openapi.yaml`) | ${apiVersion?.let { "`$it`" } ?: "No committed contract"} |")
            appendLine()
            appendLine("The running build and its Git commit are reported by `/q/openbank/docs` and `/api/v1/info`. The API contract is published at `/q/openapi` where enabled.")
        })
        if (authoredDocs.none { it.name == "README.md" || it.name == "README.en.md" || it.name == "README.cs.md" }) {
            docs.resolve("README.md").writeText(buildString {
                appendLine("# $module")
                appendLine()
                appendLine("This service publishes its own build facts and any authored documentation packaged with the service.")
                appendLine()
                appendLine("See [build facts](./00-build.md) and the service's `/q/openapi` endpoint for its generated API contract.")
            })
        }
    }
}

tasks.named<Copy>("processResources") {
    from(generatedServiceDocs.map { it.dir("docs") }) { into("docs") }
    from(generatedServiceDocs.map { it.file("openbank-service-build.properties") })
    dependsOn(generateServiceDocs)
}

val verifyServiceDocs by tasks.registering {
    group = "verification"
    description = "Verify that current service documentation is packaged in the Quarkus application JAR."
    dependsOn("quarkusBuild")
    doLast {
        val appJars = layout.buildDirectory.dir("quarkus-app/app").get().asFile
            .listFiles { file -> file.extension == "jar" }.orEmpty()
        check(appJars.size == 1) { "${project.name}: expected one Quarkus application JAR" }
        ZipFile(appJars.single()).use { jar ->
            val facts = jar.getEntry("docs/00-build.md")?.let { jar.getInputStream(it).bufferedReader().readText() }
            val properties = jar.getEntry("openbank-service-build.properties")
                ?.let { jar.getInputStream(it).bufferedReader().readText() }
            val commit = properties
                ?.let { Regex("(?m)^git\\.commit=([0-9a-fA-F]{40})$").find(it)?.groupValues?.get(1) }
            check(facts != null && facts.contains(project.version.toString())
                && commit != null && facts.contains(commit) && commit == sourceCommit.get().trim()) {
                "${project.name}: current build facts were not packaged in the Quarkus application JAR"
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyServiceDocs) }
