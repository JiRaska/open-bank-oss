// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generated, contract-bound REST client for openbank-transaction-service (ADR-0319).
//
// GENERATED from the provider's own src/main/resources/openapi.yaml with the same generator
// settings as openbank-client-product-catalog (plugin 7.24.0, `kotlin-server` / `jaxrs-spec`,
// interfaceOnly, Mutiny). Only the `Transactions` API tag is generated: the `Merchant catalogue`
// tag is operator-only and no service consumes it.
//
// `returnResponse=true` (not the pilot's typed bodies): every operation returns
// `Uni<jakarta.ws.rs.core.Response>` so the HTTP status is explicit and the body is read lazily.
// A typed `Uni<T>` fails on an unreadable 2xx body WITHOUT a status, which on a money path turns a
// booked debit into an error. The raw Response must not reach callers: `TransactionClient` is the
// single place that classifies it (Booked / Conflict / Rejected / throw) - see its KDoc. Consumers must
// NOT call the generated TransactionsApi directly.
//
// VERSIONING (ADR-0319 D2, ADR-0048): the artifact version is the provider's API-CONTRACT version,
// `info.version` of the spec, read at configuration time - never the provider's release version.
// Like the product-catalog pilot there is no version.txt: this module is not a release-please
// component.
//
// Consumers attach their own `@RegisterRestClient` sub-interface (configKey, named OIDC client,
// SyntheticTaintClientFilter and header providers are per consumer).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    id("openbank.static-analysis")
    id("openbank.dependency-vulnerability-pins")
    id("org.openapi.generator") version "7.24.0"
    `java-library`
    // No jandex plugin: applying org.kordamp.gradle.jandex next to org.openapi.generator in one
    // plugins block fails with a Banner ClassCastException (two plugin classloaders). The empty
    // src/main/resources/META-INF/beans.xml makes Quarkus index this jar instead.
}

val providerSpec = rootProject.layout.projectDirectory
    .file("openbank-transaction-service/src/main/resources/openapi.yaml").asFile

// `info.version` is the only two-space-indented `version:` key in the file (ADR-0048 layout).
val contractVersion: String = providerSpec.readLines()
    .dropWhile { it.trim() != "info:" }
    .firstOrNull { it.startsWith("  version:") }
    ?.substringAfter(":")?.trim()?.trim('"', '\'')
    ?: error("no info.version in $providerSpec — ADR-0048 requires one")

group = "com.openbank.clients"
version = contractVersion

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val generatedClient = layout.buildDirectory.dir("generated/openapi/transaction-client")

openApiGenerate {
    generatorName.set("kotlin-server")
    inputSpec.set(providerSpec.absolutePath)
    outputDir.set(generatedClient.get().asFile.absolutePath)
    cleanupOutput.set(true)
    apiPackage.set("com.openbank.clients.transaction.api")
    modelPackage.set("com.openbank.clients.transaction.model")
    packageName.set("com.openbank.clients.transaction")
    library.set("jaxrs-spec")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "returnResponse" to "true",
            "useMutiny" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "false",
            "useTags" to "true",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    globalProperties.set(
        mapOf(
            "apis" to "Transactions",
            "models" to "",
            "apiDocs" to "false",
            "modelDocs" to "false",
            "apiTests" to "false",
            "modelTests" to "false",
        ),
    )
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedClient.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerate")) }

tasks.configureEach {
    if (name == "detekt" || name.startsWith("runKtlint")) {
        dependsOn(tasks.named("openApiGenerate"))
    }
}

dependencies {
    // API types only — every consumer is a Quarkus service that brings the implementations
    // through the Quarkus platform BOM.
    compileOnly(enforcedPlatform(libs.quarkus.bom))
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api")
    compileOnly("jakarta.annotation:jakarta.annotation-api")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.smallrye.reactive:mutiny")

    testImplementation(enforcedPlatform(libs.quarkus.bom))
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api")
    testImplementation("jakarta.annotation:jakarta.annotation-api")
    testImplementation("io.smallrye.reactive:mutiny")
    // A real JAX-RS client implementation, so the facade tests run against real sockets and real Responses.
    testImplementation("io.quarkus.resteasy.reactive:resteasy-reactive-client")
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The binding test reads the committed pacts from the repository root.
    systemProperty("openbank.pacts.dir", rootProject.layout.projectDirectory.dir("pacts").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("pacts"))
}

// Generated code is not hand-maintained; lint and coverage apply to what a human writes here.
kover {
    reports {
        filters { excludes { packages("com.openbank.clients.transaction.api") } }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
