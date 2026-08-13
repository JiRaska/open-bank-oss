// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
    id("org.openapi.generator") version "7.24.0"
}

val generatedCatalogServer = layout.buildDirectory.dir("generated/openapi/catalog-server")

openApiGenerate {
    generatorName.set("kotlin-server")
    inputSpec.set(layout.projectDirectory.file("src/main/resources/openapi.yaml").asFile.absolutePath)
    outputDir.set(generatedCatalogServer.get().asFile.absolutePath)
    cleanupOutput.set(true)
    apiPackage.set("com.openbank.productcatalog.generated.api")
    modelPackage.set("com.openbank.productcatalog.generated.model")
    packageName.set("com.openbank.productcatalog.generated")
    library.set("jaxrs-spec")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "returnResponse" to "true",
            "useCoroutines" to "true",
            "useJakartaEe" to "true",
            "useBeanValidation" to "false",
            "useTags" to "true",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
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
    kotlin.srcDir(generatedCatalogServer.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks.configureEach {
    if (name == "detekt" || name.startsWith("runKtlint")) {
        dependsOn(tasks.named("openApiGenerate"))
    }
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    // Resource-server auth (issue #401): validate caller tokens for @Authenticated / @RolesAllowed.
    // The @Authorize interceptor, Roles and the Authorize annotation already arrive transitively via
    // :openbank-libs (which api-exports :openbank-libs-runtime + :openbank-libs-domain).
    implementation(libs.quarkus.oidc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation("com.networknt:json-schema-validator:1.5.9")

    // Persistence: reactive Panache + Postgres + Flyway, the fleet standard (ADR-0009/0105 P1).
    // openbank-libs is built on reactive Panache, so a service that depends on it must use reactive
    // too — a blocking ORM cannot index the libs reactive entities (JandexScavenger fails at build).
    // The Mutiny results are bridged to the suspend repository port via the coroutine adapter.
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.jdbc.postgresql) // Flyway runs migrations over JDBC
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    implementation(project(":openbank-libs"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.quarkus.test.security.oidc)

    // Shared Testcontainers resource kit (issue #467) — pilot migration off the local
    // PostgresTestResource.kt copy.
    testImplementation(project(":openbank-libs-testing"))

    // Provider replay plus the Product Studio v2 consumer contract (ADR-0063 git-pact).
    testImplementation(libs.pact.provider)
    testImplementation(libs.pact.consumer)
}

// Pact: provider verification reads pact files from the shared pacts/ dir (git-pact, ADR-0063).
// Set on the test JVM fork, not the Gradle daemon — System.setProperty would not propagate.
tasks.withType<Test> {
    // This module deliberately boots the default, empty standalone, insurance-only and
    // banking-compatibility Quarkus profiles in one CI task. Quarkus retains augmentation
    // metadata between profile restarts, so Gradle's 512 MiB test-worker default exhausts the
    // heap before the standalone boot proofs execute. This changes test infrastructure only.
    maxHeapSize = "1536m"
    systemProperty("pact.rootDir", "${rootProject.projectDir}/pacts")

    // Pact Broker verification (ADR-0092): forward the broker config CI passes with `-D`.
    // Without this the properties reach the Gradle daemon and stop there, so the @PactBroker
    // provider test is @EnabledIfSystemProperty(pactbroker.url)-skipped and pact-jvm logs
    // "Skipping publishing of verification results ... not 'true'" — even on a main push where
    // the workflow set PUBLISH_RESULTS=true. That is exactly how this module ended up with a
    // broker version carrying no branch and no verification result, leaving its consumers
    // permanently UNVERIFIED and undeployable (issue #3285).
    listOf(
        "pactbroker.url",
        "pactbroker.auth.username",
        "pactbroker.auth.password",
        "pactbroker.enablePending",
        "pactbroker.providerBranch",
        "pact.verifier.publishResults",
        "pact.provider.version",
        "pact.provider.branch",
        "pact.provider.tag",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// Coverage floor (ADR-0020, ratchet-only — sweep #466: this module previously had NO
// koverVerify gate at all). Floor = measured LINE coverage at introduction minus ~5 pt
// headroom; raise-only from here. Same excludes rationale as ledger/billing: thin REST
// adapters are covered by API ITs, reflection DTOs are data holders.
kover {
    reports {
        filters {
            excludes {
                annotatedBy("jakarta.ws.rs.Path")
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 90 // measured 95.4% (1315/1379) at introduction
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
