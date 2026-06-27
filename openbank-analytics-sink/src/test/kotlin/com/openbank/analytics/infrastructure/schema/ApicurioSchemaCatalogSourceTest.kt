// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.analytics.SchemaKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-JUnit tests for the Apicurio-backed schema catalogue source. The HTTP seam ([httpGet]) is
 * overridden to script Apicurio v2 REST responses, so artifact/version discovery and boot-resilient
 * failure handling are verified without a registry server. Response parsing is pure and tested directly.
 */
class ApicurioSchemaCatalogSourceTest {

    private val mapperFixture = ObjectMapper()

    /** Replays scripted JSON bodies keyed by request path; records every path it was asked for. */
    private class ScriptedApicurio(
        mapper: ObjectMapper,
        private val responses: Map<String, String>
    ) : ApicurioSchemaCatalogSource() {
        val calls = mutableListOf<String>()

        init {
            this.mapper = mapper
            url = "http://schema-registry:8081"
            group = "default"
        }

        override fun httpGet(path: String): String {
            calls += path
            return responses[path] ?: error("no scripted response for $path")
        }
    }

    @Test
    fun `parseArtifactIds reads ids and skips blank entries`() {
        val src = ApicurioSchemaCatalogSource().apply { mapper = mapperFixture }
        val json = """{"artifacts":[{"id":"AccountOpened"},{"id":""},{"id":"PartyRegistered"},{"name":"no-id"}]}"""

        assertThat(src.parseArtifactIds(json)).containsExactly("AccountOpened", "PartyRegistered")
    }

    @Test
    fun `parseVersionNumbers keeps numeric versions and ignores non-numeric`() {
        val src = ApicurioSchemaCatalogSource().apply { mapper = mapperFixture }
        val json = """{"versions":[{"version":"1"},{"version":"2"},{"version":"draft"},{"version":"3"}]}"""

        assertThat(src.parseVersionNumbers(json)).containsExactly(1, 2, 3)
    }

    @Test
    fun `load builds catalog from artifacts and their versions`() {
        val src = ScriptedApicurio(
            mapperFixture,
            mapOf(
                "/apis/registry/v2/groups/default/artifacts?limit=500"
                    to """{"artifacts":[{"id":"AccountOpened"},{"id":"PartyRegistered"}]}""",
                "/apis/registry/v2/groups/default/artifacts/AccountOpened/versions?limit=500"
                    to """{"versions":[{"version":"1"},{"version":"2"}]}""",
                "/apis/registry/v2/groups/default/artifacts/PartyRegistered/versions?limit=500"
                    to """{"versions":[{"version":"1"}]}"""
            )
        )

        val catalog = src.load()

        assertThat(catalog.knownEventTypes()).containsExactlyInAnyOrder("AccountOpened", "PartyRegistered")
        assertThat(catalog.isCompatible(SchemaKey("AccountOpened", 2))).isTrue()
        assertThat(catalog.isCompatible(SchemaKey("AccountOpened", 3))).isFalse()
        assertThat(catalog.isCompatible(SchemaKey("PartyRegistered", 1))).isTrue()
    }

    @Test
    fun `load returns an empty open gate when the registry is unreachable`() {
        // No scripted responses -> httpGet throws -> load() must swallow and return an empty catalog.
        val src = ScriptedApicurio(mapperFixture, emptyMap())

        val catalog = src.load()

        assertThat(catalog.knownEventTypes()).isEmpty()
    }
}
