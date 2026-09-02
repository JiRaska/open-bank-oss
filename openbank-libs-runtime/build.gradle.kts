// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
    // Domain module — pure Kotlin/JVM; re-exported as api() so dependents on runtime
    // also see domain types without an explicit dependency.
    api(project(":openbank-libs-domain"))

    // EXCEPTION: BearerTokenClientHeadersFactory implements microprofile-rest-client's
    // ClientHeadersFactory. Shipped transitively as api() — see openbank-libs build.gradle.kts
    // for the full rationale (NoClassDefFoundError on services without rest-client extension).
    api("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")

    // Framework APIs — compileOnly (services provide impls via Quarkus platform BOM).
    // Versions MUST equal what the CURRENT quarkus-bom (libs.versions.toml: quarkus) ships —
    // these literals do not float with a platform bump, so they rot silently. Caught live
    // 2026-08: this block's io.micrometer:micrometer-core stayed pinned to 1.14.5 across the
    // Quarkus 3.33.2->3.38.0 bump (#2700), long after quarkus-bom:3.38.0 started managing
    // 1.17.0 for every service. Nothing here failed to compile — compileOnly/testImplementation
    // don't reach a consuming service's runtime classpath — but this module's OWN dependency
    // graph submission still reported the stale 1.14.5, and a later-disclosed GHSA against
    // the 1.14.x line (CVE-2026-40984, no patched release on that line at all) turned six
    // months of unnoticed drift into a fleet-wide dependency-review failure on every PR
    // (issue #5482). Re-check this whole block against the BOM's actual managed versions on
    // every Quarkus platform bump, not just the ones a compile error would catch.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    // @InterceptorBinding / @Nonbinding for the Authorize and FeatureFlag annotations, which
    // moved here from libs-domain in #3670 (CDI is framework — ADR-0122's runtime side).
    // Declared explicitly rather than leaned on transitively via cdi-api.
    compileOnly("jakarta.interceptor:jakarta.interceptor-api:2.2.0")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("org.eclipse.microprofile.config:microprofile-config-api:3.1")
    compileOnly("org.jboss.logging:jboss-logging:3.6.2.Final")
    compileOnly("io.quarkus:quarkus-redis-client:3.33.2")
    compileOnly("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:4.0")
    compileOnly("io.quarkus:quarkus-hibernate-reactive-panache-kotlin:3.33.2")
    // Test-only, and deliberately not `implementation`: the persistence exception mappers reference
    // Hibernate types, but a service without an ORM must not inherit one from libs-runtime just to
    // get the shared error handling. compileOnly keeps it off every consumer's runtime classpath;
    // this line only lets libs-runtime's OWN tests construct a DataException to assert against.
    testImplementation("io.quarkus:quarkus-hibernate-reactive-panache-kotlin:3.33.2")
    compileOnly("io.quarkus:quarkus-scheduler:3.33.2")
    compileOnly("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1.1")
    // 1.14.5 -> 1.17.0: GHSA-g3pr-3p32-fp23 / CVE-2026-40984 (HIGH, DoS in Micrometer's HTTP
    // server instrumentations). The 1.14.x line has NO fix (advisory's first_patched_version
    // is null for 1.14.0-1.14.14); the fix landed in 1.15.12+/1.16.6+. This module doesn't apply
    // the Quarkus platform BOM (it's a plain kotlin-jvm library, not a Quarkus service), so this
    // literal is what actually reaches the dependency graph dependency-review scans — NOT the
    // quarkus-bom:3.38.0 constraint every real service resolves through
    // (`./gradlew :openbank-account-service:dependencyInsight --dependency io.micrometer:micrometer-core`
    // confirms real services already land on 1.17.0). 1.17.0 matches that resolved version
    // exactly, consistent with this block's "MUST equal what quarkus-bom ships" convention.
    // Issue #5482.
    compileOnly("io.micrometer:micrometer-core:1.17.0")
    compileOnly("io.quarkus:quarkus-security:3.33.2")
    compileOnly("io.quarkus:quarkus-arc:3.33.2")
    // SyntheticTaintRequestFilter binds the trusted synthetic classification into OTel baggage
    // for the lifetime of an inbound request. Keep this compileOnly: Quarkus services already
    // supply the API at runtime, and libs-runtime must not bring an observability SDK with it.
    compileOnly("io.opentelemetry:opentelemetry-api:1.62.0")

    // NulByteGuards: the fleet-wide U+0000 rejection (#5913). jackson-databind supplies
    // StringDeserializer/SimpleModule; quarkus-jackson supplies ObjectMapperCustomizer, the
    // registration hook. Both compileOnly for the same reason as everything above — every real
    // service already brings them via quarkus-rest-jackson.
    //
    // quarkus-jackson is pinned to 3.33.2, matching every sibling literal in this block rather
    // than the 3.38.0 the platform actually resolves. That is deliberate and measured, not the
    // rot this block's header warns about: resolving quarkus-jackson:3.38.0 STANDALONE (this
    // module applies no Quarkus BOM) drags four POMs that `gradle/verification-metadata.xml` does
    // not carry — io.smallrye.common:smallrye-common-{classloader,expression,function}:2.17.1 and
    // smallrye-common-os:2.15.0 — and dependency verification then fails the build of every
    // consuming service. Only the ObjectMapperCustomizer interface is compiled against, and it is
    // identical across both versions. Correcting the whole block to the real BOM versions is the
    // separate change the header calls for (#5482), and needs those checksums added with it.
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    compileOnly("io.quarkus:quarkus-jackson:3.33.2")

    // S3ObjectStore (ADR-0161 D2) compiles against the real AWS SDK v2 `s3` module
    // (S3Presigner ships in the same artifact — no separate presigner dependency).
    // compileOnly, matching the convention above: this is NOT part of the Quarkus
    // platform BOM, so bundling it as implementation()/api() here would put the whole
    // AWS SDK on every consuming service's runtime classpath whether or not that service
    // ever selects the s3 backend. A service that sets openbank.objectstore.backend=s3
    // must add libs.aws.sdk.s3 (or an equivalent) to its OWN build.gradle.kts.
    compileOnly(libs.aws.sdk.s3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.2.0")
    testImplementation("io.quarkus:quarkus-security:3.33.2")
    testImplementation("org.jboss.resteasy:resteasy-core:6.2.12.Final")
    testImplementation("org.jboss.logging:jboss-logging:3.6.2.Final")
    testImplementation("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    // Kept in sync with the compileOnly pin above — CVE-2026-40984, issue #5482.
    testImplementation("io.micrometer:micrometer-core:1.17.0")
    // RedisApprovalStoreTest drives the REAL store — the four-eyes self-approval guard is the
    // single fleet-wide enforcement point for segregation of duties (#3349), and until that test
    // existed deleting it left every suite green. Both are compileOnly above, so the test source
    // set needs them explicitly; same pattern as quarkus-security and the FT API here.
    testImplementation("io.quarkus:quarkus-redis-client:3.33.2")
    // NulByteGuardsTest drives the REAL ObjectMapper through the REAL customizer, so the module
    // registration and the deserializer are both exercised rather than asserted about.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    testImplementation("io.quarkus:quarkus-jackson:3.33.2")
    testImplementation("io.opentelemetry:opentelemetry-api:1.62.0")
    testImplementation("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    // ResilientCallMetrics classifies CircuitBreakerOpenException; the API is compileOnly above.
    testImplementation("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1.1")
    // Test-only: WorkflowLivenessMetricNamingTest checks the dotted meter name against Micrometer's
    // REAL PrometheusNamingConvention rather than trusting the hand-rolled dot -> underscore
    // rendering that the sentinel's PromQL depends on. The registry itself is never used at runtime
    // here — each service brings quarkus-micrometer-registry-prometheus itself.
    testImplementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // OutboxDeadLetterAlertNamingTest asserts that the committed PrometheusRule selector matches
    // what a real Micrometer registration exports — a producer/consumer seam whose two halves live
    // in different trees. Without declaring the rule file as an input, Gradle sees no reason to
    // re-run: editing the alert to `openbank_TOTALLY_WRONG` reports `test UP-TO-DATE` and BUILD
    // SUCCESSFUL, and only `--rerun-tasks` goes red. Combined with path-scoped CI — a gitops-only
    // PR never builds this module — the guard could not see the change it exists to catch.
    inputs.file(rootProject.file("openbank-infra/gitops/components/payments/prometheus-rules.yaml"))
        .withPropertyName("alertRulesUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("openbank-infra/gitops/components/billing/prometheus-rules-billing.yaml"))
        .withPropertyName("billingAlertRulesUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `every dead-letter gauge binding is covered here` derives its scope by walking the service
    // modules for `*OutboxDeadLetterGauge.kt`. Without these as declared inputs the task is
    // UP-TO-DATE when a NEW binding appears — measured: adding an uncovered gauge left the suite
    // green, so the scope guard was blind to the one event it exists to catch.
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("openbank-*/src/main/kotlin/**/*OutboxDeadLetterGauge.kt")
        },
    ).withPropertyName("deadLetterGaugeBindings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

kover {
    reports {
        filters {
            excludes {
                classes("com.openbank.libs.web.ServiceInfoResource")
                classes("com.openbank.libs.persistence.outbox.PanacheOutboxEntity")
                classes("com.openbank.libs.persistence.outbox.AbstractOutboxEntity")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 50
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
