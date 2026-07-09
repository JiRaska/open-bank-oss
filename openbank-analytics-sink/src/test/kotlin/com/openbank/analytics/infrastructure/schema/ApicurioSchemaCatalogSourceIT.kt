// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.infrastructure.support.KGenericContainer
import com.openbank.libs.analytics.SchemaKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end verification of [ApicurioSchemaCatalogSource] (ADR-0023 F7) against a real Apicurio
 * Registry. The unit test stubs the [ApicurioSchemaCatalogSource.httpGet] seam; this drives the
 * **actual** v2 REST traversal (`/groups/{group}/artifacts` → `.../{id}/versions`) against a live
 * registry seeded with real artifacts and versions, proving the governed [com.openbank.libs.analytics.SchemaCatalog]
 * is assembled correctly — including the multi-version case the seam test cannot fully exercise.
 *
 * Self-skips when Docker is absent, so the offline build is unaffected.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ApicurioSchemaCatalogSourceIT {

    companion object {
        private const val GROUP = "default"
        private const val EVT_ACCOUNT = "account.account.opened"
        private const val EVT_PARTY = "party.party.created"

        @Container
        @JvmStatic
        private val apicurio: KGenericContainer =
            KGenericContainer("apicurio/apicurio-registry-mem:2.6.2.Final")
                .withExposedPorts(8080)
                // Apicurio is a prod-mode Quarkus/JVM app whose cold boot is CPU-bound. On a busy host
                // (many other containers competing for cores) it has been seen taking 170s+ with vert.x
                // blocked-thread warnings before it logs "started in …s / Listening on :8080". Tiered
                // compilation stop-at-1 cuts JIT startup work so the event loops aren't starved, which
                // shortens cold boot markedly under contention; pair it with a generous probe window so
                // a starved host still clears the bar (an idle CI box boots in well under a minute).
                .withEnv("JAVA_OPTS_APPEND", "-XX:TieredStopAtLevel=1")
                .waitingFor(
                    Wait.forHttp("/apis/registry/v2/system/info")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(8)),
                )

        private fun baseUrl() = "http://${apicurio.host}:${apicurio.getMappedPort(8080)}"

        private val http: HttpClient = HttpClient.newHttpClient()

        private fun createArtifact(eventType: String, schema: String): Int = send(
            "POST",
            "/apis/registry/v2/groups/$GROUP/artifacts",
            schema,
            mapOf("X-Registry-ArtifactId" to eventType, "X-Registry-ArtifactType" to "JSON"),
        )

        private fun addVersion(eventType: String, schema: String): Int = send(
            "POST",
            "/apis/registry/v2/groups/$GROUP/artifacts/$eventType/versions",
            schema,
            mapOf("X-Registry-ArtifactType" to "JSON"),
        )

        private fun send(method: String, path: String, body: String, headers: Map<String, String>): Int {
            var b = HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
                .header("Content-Type", "application/json")
            headers.forEach { (k, v) -> b = b.header(k, v) }
            val resp = http.send(
                b.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            return resp.statusCode()
        }

        @BeforeAll
        @JvmStatic
        fun seedRegistry() {
            // account.account.opened: two registered versions (v1, v2).
            assertThat(createArtifact(EVT_ACCOUNT, """{"type":"object","title":"opened-v1"}""")).isIn(200, 201)
            assertThat(addVersion(EVT_ACCOUNT, """{"type":"object","title":"opened-v2"}""")).isIn(200, 201)
            // party.party.created: a single version (v1).
            assertThat(createArtifact(EVT_PARTY, """{"type":"object","title":"created-v1"}""")).isIn(200, 201)
        }
    }

    private fun source() = ApicurioSchemaCatalogSource().apply {
        url = baseUrl()
        group = GROUP
        mapper = ObjectMapper()
    }

    @Test
    fun `load assembles the governed catalog from the live registry over real HTTP`() {
        val catalog = source().load()

        // Every seeded (eventType, version) must be present and exactly known.
        assertThat(catalog.isKnown(SchemaKey(EVT_ACCOUNT, 1))).isTrue()
        assertThat(catalog.isKnown(SchemaKey(EVT_ACCOUNT, 2))).isTrue()
        assertThat(catalog.isKnown(SchemaKey(EVT_PARTY, 1))).isTrue()
        assertThat(catalog.knownEventTypes()).contains(EVT_ACCOUNT, EVT_PARTY)

        // A version newer than anything registered is treated as incompatible (quarantine, not write).
        assertThat(catalog.isCompatible(SchemaKey(EVT_ACCOUNT, 2))).isTrue()
        assertThat(catalog.isCompatible(SchemaKey(EVT_PARTY, 2))).isFalse()
        assertThat(catalog.isKnown(SchemaKey("never.registered", 1))).isFalse()
    }
}
