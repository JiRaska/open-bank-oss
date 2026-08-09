// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Fleet-wide static analysis (SAST gate). Applied by openbank.quarkus-service (every
// service) and directly by the non-convention modules (openbank-libs,
// openbank-analytics-sink). This makes the documented local gate
// `./gradlew detekt ktlintCheck` real — both tasks are also wired into `check`,
// so the per-service CI invocation `:<service>:build` enforces them.
//
// Ratchet posture: existing findings are frozen in per-module baseline files
// (detekt-baseline.xml / ktlint-baseline.xml in each module dir); anything NEW
// fails the build. Never add to a baseline by hand — fix the finding. Regenerate
// only when deliberately ratcheting down:
//   ./gradlew detektBaseline ktlintGenerateBaseline
//
// Rule config: detekt rules live in config/detekt/detekt.yml (repo root, correctness
// focus, overlay on detekt's default config); ktlint is .editorconfig-driven
// (formatting). detekt's formatting ruleset is intentionally NOT applied — ktlint
// owns formatting.
//
// WHY detekt runs as a forked JavaExec instead of via the detekt Gradle plugin:
// detekt 1.23.8 (the newest release) bundles kotlin-compiler-embeddable 2.0.x whose
// IntelliJ JavaVersion.parse() hard-rejects JVMs newer than it knows — observed as
// `IllegalArgumentException: 25.0.3` / `26.0.1` when the in-process CLI runs on the
// Temurin 25/26 daemons used on dev machines and CI. Forcing a newer compiler onto
// the CLI classpath trips detekt's own "compiled with Kotlin 2.0.21 but running
// with X" equality check, and there is no detekt 2.x release yet. Forking the CLI
// onto a Java 21 toolchain (auto-provisioned by the foojay resolver; preinstalled
// on CI runners) sidesteps the daemon JVM entirely and is stable no matter which
// JDK Gradle itself runs on. Revisit when detekt 2.x ships.

import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

// ---------------------------------------------------------------------------------
// detekt (forked CLI)
// ---------------------------------------------------------------------------------

val detektVersion = the<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("detekt")
    .orElseThrow { IllegalStateException("detekt version missing in libs.versions.toml") }
    .requiredVersion

val detektCli: Configuration = configurations.create("detektCli") {
    isCanBeConsumed = false
    description = "The detekt CLI classpath (forked, isolated from the build classpath)."
}

dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:$detektVersion")
    // ADR-0219 D4's compile-time wiring assertion (openbank-libs-detekt-rules), loaded onto
    // every module's forked detekt CLI classpath via ServiceLoader (META-INF/services) — including
    // the rule module's own detekt task: config/detekt/detekt.yml's `openbank-contact-policy` key
    // is fleet-wide, and detekt fails config validation ("Property ... is misspelled or does not
    // exist") on any module whose classpath doesn't know that rule set id, self included.
    detektCli(project(":openbank-libs-detekt-rules"))
}

// rootProject here is the main openbank build (build-logic is a composite, but this
// plugin executes inside the consuming project).
val detektConfigFile = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
val detektBaselineFile = layout.projectDirectory.file("detekt-baseline.xml").asFile
val detektReportDir = layout.buildDirectory.dir("reports/detekt")
val detektSources = layout.projectDirectory.dir("src")

// detekt's embedded compiler is happy on a 21 launcher; CI installs Temurin 21
// alongside 25, and dev machines get it auto-provisioned via the foojay resolver
// already configured in settings.gradle.kts.
val detektLauncher = the<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

fun JavaExec.commonDetektSetup() {
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    javaLauncher.set(detektLauncher)
    onlyIf("module has a src directory") { detektSources.asFile.isDirectory }
}

val detektArgsBase = buildList {
    add("--input"); add(detektSources.asFile.absolutePath)
    add("--config"); add(detektConfigFile.asFile.absolutePath)
    add("--build-upon-default-config")
    add("--parallel")
    // Generated sources (Quarkus/Avro) land under build/ — never analyse them.
    add("--excludes"); add("**/build/**,**/generated/**")
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs detekt static analysis (ratchet: pre-existing findings live in detekt-baseline.xml)."
    commonDetektSetup()

    inputs.files(fileTree(detektSources) { include("**/*.kt", "**/*.kts") })
        .withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(detektConfigFile).withPropertyName("detektConfig").withPathSensitivity(PathSensitivity.NONE)
    if (detektBaselineFile.exists()) {
        inputs.file(detektBaselineFile).withPropertyName("detektBaseline").withPathSensitivity(PathSensitivity.NONE)
    }
    outputs.dir(detektReportDir).withPropertyName("detektReports")

    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(detektArgsBase)
            add("--report"); add("html:${detektReportDir.get().asFile.resolve("detekt.html")}")
            add("--report"); add("sarif:${detektReportDir.get().asFile.resolve("detekt.sarif")}")
            // Only consume the baseline where one exists; a module with zero
            // historical findings has no baseline file and gates everything.
            if (detektBaselineFile.exists()) {
                add("--baseline"); add(detektBaselineFile.absolutePath)
            }
        }
    })
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Regenerates detekt-baseline.xml (deliberate ratchet-down only — never to silence new findings)."
    commonDetektSetup()

    argumentProviders.add(
        CommandLineArgumentProvider {
            buildList {
                addAll(detektArgsBase)
                add("--create-baseline")
                add("--baseline"); add(detektBaselineFile.absolutePath)
            }
        },
    )
}

tasks.named("check") {
    dependsOn("detekt")
}

// ---------------------------------------------------------------------------------
// ktlint (formatting; .editorconfig-driven)
// ---------------------------------------------------------------------------------

ktlint {
    // The baseline freezes pre-existing violations per module; regenerate with
    // ./gradlew ktlintGenerateBaseline.
    baseline.set(file("ktlint-baseline.xml"))
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}
