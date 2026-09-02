// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("openbank.static-analysis")
    // Same gap as openbank-simulation (see the pins file's own comment): force() is
    // project-local, so a project(...) consumer's force never reaches this module's OWN
    // standalone resolution. This module isn't a Quarkus service (no openbank.quarkus-service
    // to carry the pins transitively) and pulls testcontainers -> docker-java directly, which
    // resolves its own old netty-handler:4.1.133.Final/etc — invisible to every consuming
    // service (their own force always wins in their own resolution) but still submitted to
    // GitHub's dependency graph as this module's standalone testRuntimeClasspath, keeping
    // Dependabot alerts open fleet-wide. (#1728 already fixed this same gap narrowly for
    // postgresql alone; this closes it for every other pin in the shared file at once.)
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
    api(libs.kotlin.reflect)

    // Test-framework surface the conformance kits expose in their public abstract classes —
    // `api` so a consuming service's testImplementation(project(":openbank-libs-testing"))
    // pulls these transitively without redeclaring them.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj)

    // Trace-contract kit exposes SpanData/SpanExporter in its public test API. Keep the
    // SDK version aligned with the fleet's Quarkus OpenTelemetry line rather than making
    // every consumer reinvent an in-memory exporter and ad-hoc span assertions.
    api("io.opentelemetry:opentelemetry-sdk:1.62.0")

    // AuditEventTime parses a producer's real outbox payload with the same Jackson the audit
    // consumer uses. jsr310 (not bare databind) because it carries jackson-databind/core
    // transitively at the catalog's pinned `jackson` version, and this module has no Quarkus BOM
    // to align them — the same reason the direct pins further down are spelled out.
    api(libs.jackson.datatype.jsr310)

    // JAX-RS + CDI security annotation types the authz conformance kit reflects over
    // (jakarta.ws.rs.GET/POST/.../jakarta.annotation.security.PermitAll/RolesAllowed).
    // No Quarkus BOM here (this module isn't a Quarkus service), so pinned directly —
    // same coordinates openbank-libs-runtime already uses for the identical types.
    api("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    api("jakarta.annotation:jakarta.annotation-api:3.0.0")

    // Money test-data builders + outbox dispatch conformance kit (issue #467) — Money/
    // CurrencyCode/OutboxMessage/OutboxEntry/OutboxKafkaHeaders are all genuinely shared
    // domain types (openbank-libs-domain), unlike JournalEntry/JournalLine which live
    // per-service.
    api(project(":openbank-libs-domain"))
    // Same version pin as openbank-ledger-service/openbank-balance-service's own property
    // suites and openbank-libs-domain's MoneyPropertyTest — kept a direct GAV like theirs.
    api("io.kotest:kotest-property:5.9.1")
    // quarkus-vertx: VertxContextSupport (reactive Panache needs a Vert.x duplicated context).
    // quarkus-messaging-kafka: OutgoingKafkaRecordMetadata (kafka-api), for reading the produced
    // record's key/headers back out of the in-memory connector's sink.
    // No Quarkus BOM here (this module isn't a Quarkus service), so pinned directly, matching
    // libs.versions.toml's quarkus = "3.33.2".
    api("io.quarkus:quarkus-vertx:3.33.2")
    api("io.quarkus:quarkus-messaging-kafka:3.33.2")
    // Bridges a Kotlin suspend block into the Uni VertxContextSupport.subscribeAndAwait expects
    // (io.smallrye.mutiny.coroutines.uni), matching LedgerOutboxDispatchIT's own pattern.
    api("io.smallrye.reactive:mutiny-kotlin:3.1.1")
    // Same version-pin situation as quarkus-vertx/quarkus-messaging-kafka above — this catalog
    // alias also has no version.ref (every other consumer relies on the Quarkus BOM).
    api("io.smallrye.reactive:smallrye-reactive-messaging-in-memory:4.33.0")

    // Testcontainers resource kit — QuarkusTestResourceLifecycleManager (from quarkus-junit5,
    // which transitively brings quarkus-test-common) + the container types the canonical
    // Postgres*TestResource classes provision. No Quarkus BOM here either; a consuming
    // service's own enforcedPlatform(libs.quarkus.bom) aligns final resolved versions.
    // Version pinned directly (no version.ref on this catalog alias — every other consumer
    // relies on enforcedPlatform(libs.quarkus.bom), which this non-Quarkus module doesn't
    // apply). Matches libs.versions.toml's quarkus = "3.33.2".
    // quarkus-test-common (not the heavier quarkus-junit5) — just enough for
    // QuarkusTestResourceLifecycleManager / ResourceArg, without quarkus-junit5's full
    // RestAssured + JUnit5-extension transitive graph, which pulled in an older docker-java
    // that shadowed this module's own constraint below.
    api("io.quarkus:quarkus-test-common:3.33.2")
    api(libs.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.redpanda)

    // testcontainers:1.20.4 transitively pulls docker-java-*:3.4.0, whose default API-version
    // negotiation is rejected by newer Docker daemons requiring a minimum API >= 1.40
    // ("client version 1.32 is too old") — the exact failure this constraint fixes, confirmed
    // by diffing resolved versions against a real Quarkus service (BOM-aligned to 3.7.1, works
    // fine) vs this module unconstrained (3.4.0, fails). Every BOM-aligned consumer never hits
    // this because enforcedPlatform(libs.quarkus.bom) already pins 3.7.1; this module has no
    // BOM, so pin explicitly instead.
    constraints {
        api("com.github.docker-java:docker-java-api:3.7.1")
        api("com.github.docker-java:docker-java-transport:3.7.1")
        api("com.github.docker-java:docker-java-transport-zerodep:3.7.1")
    }

    // Real JDBC round-trip in the kit's own self-test (PostgresTestResourcesTest) — not
    // pulled transitively by testcontainers-postgresql, which only drives the container.
    // Declared version matches the same patched line openbank.dependency-vulnerability-pins
    // forces fleet-wide (issue #461, now applied to this module too — see the plugins block
    // above) purely for readability; force() would win over any version requested here anyway.
    testImplementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") }
}
