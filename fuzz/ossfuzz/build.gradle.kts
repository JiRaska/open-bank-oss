// SPDX-License-Identifier: Apache-2.0
// OSS-Fuzz harness module — deliberately NOT included in the root settings.gradle.kts:
// it is built only by the OSS-Fuzz build container (fuzz/ossfuzz/ossfuzz-build.sh) and
// by developers running the fuzzers locally. Keeping it out of the root build means no
// impact on path-scoped CI, release-please, or the version catalog.
//
// Local run (example):
//   cd fuzz/ossfuzz && gradle shadowJar
//   jazzer --cp=build/libs/ossfuzz-all.jar \
//          --target_class=com.openbank.fuzz.Pacs008ReaderFuzzer
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
}

dependencies {
    // The fuzzed code: libs-domain classes, compiled by the parent build and consumed
    // as a file dependency so this module needs no inclusion in the root project.
    implementation(files("../../openbank-libs-domain/build/classes/kotlin/main"))
    implementation("com.code-intelligence:jazzer-api:0.22.1")
    // libs-domain's own compile-time deps used by the fuzzed classes
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
}

kotlin {
    // Must match openbank-libs-domain's toolchain (25) to link against its bytecode;
    // the OSS-Fuzz Dockerfile installs Temurin 25 (see ossfuzz-build.sh).
    jvmToolchain(25)
}

tasks.shadowJar {
    archiveBaseName.set("ossfuzz")
    archiveClassifier.set("all")
}
