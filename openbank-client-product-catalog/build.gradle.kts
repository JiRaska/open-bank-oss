// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generated, contract-bound REST client for openbank-product-catalog (ADR-0319, pilot).
//
// The client is GENERATED from the provider's own src/main/resources/openapi.yaml; nothing in
// this module is hand-written except the contract-binding test. It reuses the provider's
// generator exactly — same plugin version (7.24.0), same `kotlin-server` / `jaxrs-spec` library,
// same API tags and model subset the provider already generates its server stubs from — so the
// DTOs here are the wire types the provider itself serialises. Only three options differ, all
// for the client side: interfaceOnly stays, `returnResponse=false` (typed bodies, not
// jakarta.ws.rs.core.Response), and `useMutiny=true` instead of `useCoroutines` because every
// hand-written ProductCatalogClient in the fleet returns `Uni<...>` (ADR-0319, "Runtime
// readiness": match the consumers' client mode rather than impose a new one).
//
// VERSIONING (ADR-0319 D2, ADR-0048): the artifact version is the provider's API-CONTRACT version,
// `info.version` of the spec, read at configuration time — never the provider's release version.
// There is deliberately no version.txt: this module is not a release-please component.
//
// Consumers attach their own `@RegisterRestClient` sub-interface (configKey, auth and host-header
// providers are per consumer). No consumer is migrated in the pilot PR; see the PR body for why.

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
    .file("openbank-product-catalog/src/main/resources/openapi.yaml").asFile

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

val generatedClient = layout.buildDirectory.dir("generated/openapi/product-catalog-client")

openApiGenerate {
    generatorName.set("kotlin-server")
    inputSpec.set(providerSpec.absolutePath)
    outputDir.set(generatedClient.get().asFile.absolutePath)
    cleanupOutput.set(true)
    apiPackage.set("com.openbank.clients.productcatalog.api")
    modelPackage.set("com.openbank.clients.productcatalog.model")
    packageName.set("com.openbank.clients.productcatalog")
    library.set("jaxrs-spec")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "returnResponse" to "false",
            "useMutiny" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "false",
            "useTags" to "true",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    // The same API/model subset openbank-product-catalog generates its server from. Widening it
    // (the v1 Products tag) is a separate step: those schemas are consumed today by lenient
    // hand-written DTOs that the generated, spec-strict ones would not match (see PR body).
    globalProperties.set(
        mapOf(
            "apis" to "CatalogV2,CatalogEvents",
            "models" to listOf(
                "EligibilityRule",
                "EligibilityOperator",
                "ApiError",
                "CatalogEvent",
                "CatalogEventPage",
                "CatalogSchema",
                "CatalogValidationProblem",
                "MarketContext",
                "Offering",
                "OfferingRelationship",
                "OfferingRequest",
                "PriceComponent",
                "ProductRevision",
                "PublishRequest",
                "RevisionRequest",
                "RevisionContent",
                "SchemaRef",
                "SchemaViolation",
                "Specification",
                "SpecificationRequest",
                "ValidateCatalogResponse",
                "ValidateCatalogRequest",
            ).joinToString(","),
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
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
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
        filters { excludes { packages("com.openbank.clients.productcatalog.api") } }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
