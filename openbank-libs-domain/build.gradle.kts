// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
    alias(libs.plugins.kover)
    id("openbank.static-analysis")
    // Fleet-wide Netty/Jackson/etc. patch-version floors (issue #461).
    id("openbank.dependency-vulnerability-pins")
    `java-library`
    `maven-publish`
}

group = "com.openbank"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.kotlinx.coroutines.core)
    // The identifier API still carries Jackson annotations. Serialization engines
    // belong to libs-runtime; pure finance/lending/ISO consumers do not need them.
    // Annotations use an independent minor-only version, already integrity-pinned.
    api("com.fasterxml.jackson.core:jackson-annotations:2.22")

    // UUIDv7 generation for time-ordered identifiers (ADR-0106).
    // Internal detail of domain.identifiers.Ids — services mint ids via EntityId.random() factories.
    implementation(libs.java.uuid.generator)

    // NO framework dependencies here, not even compileOnly ones: this is the domain side of the
    // ADR-0122 split and ADR-0002 says it has zero framework imports (#3670). jboss-logging was
    // replaced by the JDK's System.Logger in OutboxDispatch/FlagExposure/StaticServiceTokenProvider,
    // and the CDI interceptor bindings (@Authorize, @FeatureFlag) moved to openbank-libs-runtime
    // where @InterceptorBinding belongs. `check-domain-purity.py` now enforces this module-wide.
    //
    // The follow-up to #3670 paid off the eight baselined Jackson references the same way. Moved
    // to openbank-libs-runtime, PACKAGE UNCHANGED so no consumer import moved: the ApiError and
    // CursorPage wire DTOs (they ARE the serialization boundary), and the OpaSidecarPolicyDecision-
    // Point / FlagdProvider HTTP adapters — their PORTS (PolicyDecisionPoint, FeatureClient) stay
    // here, which is the direction the hexagon wants. CompliancePackParser was SPLIT instead of
    // moved: its decoder already took an already-parsed Map, so only the JSON front-end left, as
    // `CompliancePackJson` (now in lending-service; the parser itself in libs-lending, ADR-0317). Jackson survives below ONLY as the annotation-level
    // dependency of EntityId/LendingIds, which are still baselined and still owed a fix. Money's
    // binding was paid off the same way: it is `MoneyJacksonModule` in openbank-libs-runtime.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // Verify the existing wire contracts without exporting their engines to main.
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
    // FlagdProviderTest and OpaSidecarPolicyDecisionPointTest drive real HttpClient calls over
    // a mock server; no framework needed — they are pure JVM.
    // Property-based tests on Money arithmetic invariants (ADR-0011 L1, issue #469). Same
    // version pin as openbank-ledger-service/openbank-balance-service's JournalEntryPropertyTest/
    // BalancePropertyTest — kept as a direct GAV like theirs rather than the shared catalog, since
    // only the handful of services with money-invariant property suites need it.
    testImplementation("io.kotest:kotest-property:5.9.1")
    // ADR-0352: the product-IR JSON Schema (src/main/resources/product-ir) is held in step with the
    // Kotlin parser by ProductIrSchemaAgreementTest, which validates the fixtures with the same
    // validator product-catalog's JsonSchemaCatalogValidator uses. TEST ONLY — main stays Jackson-free
    // (the domain-purity gate), which is why the parser is hand-written (IrJson).
    testImplementation("com.networknt:json-schema-validator:1.5.9") {
        // YAML schemas are not used (the IR schema is JSON); the transitive 2.18.x yaml dataformat is
        // not in gradle/verification-metadata.xml and the fleet pins force jackson core/databind anyway.
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Build-time tech-stack snapshot read by com.openbank.libs.util.BuildInfo and served at
// /api/v1/info (admin-ui System Health + Tech Inventory). It MUST be stamped in this module,
// next to BuildInfo: every service has libs-domain on its classpath, while the umbrella
// openbank-libs JAR it used to live in is absent from services that depend on domain/runtime
// directly — they reported Quarkus, Gradle and libs as `unknown`. Values come from the version
// catalog so a bump in one place flows straight to the endpoint. No git commit or wall clock is
// stamped: either would change this JAR on every build and invalidate every service's test
// classpath; the running service's commit comes from openbank-service-build.properties.
val kotlinFromCatalog = versionCatalogs.named("libs").findVersion("kotlin").get().requiredVersion
val quarkusFromCatalog = libs.versions.quarkus.asProvider().get()
val gradleVersionStamp: String = gradle.gradleVersion

tasks.processResources {
    inputs.property("kotlinVersion", kotlinFromCatalog)
    inputs.property("quarkusVersion", quarkusFromCatalog)
    inputs.property("gradleVersion", gradleVersionStamp)
    inputs.property("libsVersion", project.version.toString())
    filesMatching("openbank-build-info.properties") {
        filter(
            org.apache.tools.ant.filters.ReplaceTokens::class,
            "tokens" to mapOf(
                "kotlinVersion" to kotlinFromCatalog,
                "quarkusVersion" to quarkusFromCatalog,
                "quarkusLts" to "true",
                "quarkusSupportUntil" to "2027-09-30",
                "gradleVersion" to gradleVersionStamp,
                "libsVersion" to project.version.toString(),
            ),
        )
    }
}

val domainRuntimeArtifacts = configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

val verifyDomainRuntimeDependencies by tasks.registering {
    group = "verification"
    description = "Keep serialization engines and Kotlin reflection outside the domain runtime boundary."
    doLast {
        val artifacts = domainRuntimeArtifacts.get()
        val dependencies = artifacts.map { artifact ->
            val identity = artifact.id.componentIdentifier
            check(identity is ModuleComponentIdentifier) { "Domain runtime dependency has no external module identity" }
            identity
        }
        val forbidden = dependencies.filter { dependency ->
            (dependency.group.startsWith("com.fasterxml.jackson.") && dependency.module != "jackson-annotations") ||
                (dependency.group == "org.jetbrains.kotlin" && dependency.module == "kotlin-reflect")
        }
        check(forbidden.isEmpty()) {
            "Domain runtime dependencies exceed the annotation-only serialization boundary: $forbidden"
        }
        logger.lifecycle("Domain runtime dependency boundary verified across {} artifacts", artifacts.size)
    }
}

tasks.named("check") {
    dependsOn(verifyDomainRuntimeDependencies)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")
    // BuildInfoTest asserts the stamped values against the catalog, so a resource that is not
    // stamped (or not on the classpath) fails the build instead of shipping `unknown`.
    systemProperty("expected.kotlin.version", kotlinFromCatalog)
    systemProperty("expected.quarkus.version", quarkusFromCatalog)
    systemProperty("expected.gradle.version", gradleVersionStamp)
    systemProperty("expected.libs.version", project.version.toString())
}

kover {
    currentProject {
        createVariant("productIr") {
            add("jvm")
        }
    }
    reports {
        // Re-measured 2026-09-29 on main merged with feat/spend-category-vocabulary's split of
        // libs-domain into libs-lending/libs-iso20022 (over two independent koverXmlReport runs,
        // both 89.4759% LINE, no variance observed): floor = floor(min(run1, run2)) - 2, per the
        // fleet's flaky-koverVerify ratchet convention. Ratchet-only: never lower this.
        verify {
            rule {
                bound {
                    minValue = 87
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
        // ADR-0352 §8: the product IR package (parser, canonical hash, pure interpreter) carries
        // its own floor, FIXED at 90 by the ADR rather than ratcheted up from a first measurement;
        // coverage-floor-ratchet then keeps it from moving down. Kover 0.9 scopes filters per report
        // variant, not per rule, so the package floor is its own variant (`koverVerifyProductIr`,
        // run by the aggregate `koverVerify`). Independent of the module floor above.
        variant("productIr") {
            filters {
                includes {
                    packages("com.openbank.libs.product")
                }
            }
            verify {
                rule("product-ir-package-floor") {
                    bound {
                        minValue = 90
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    }
                }
            }
        }
    }
}

kotlin {
    // Track the public JVM surface; check/build reject unreviewed ABI drift.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
