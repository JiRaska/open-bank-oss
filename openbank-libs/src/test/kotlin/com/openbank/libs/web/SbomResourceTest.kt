// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

// Plain unit test (no Quarkus bootstrap) — exercises the file-serving contract of
// SbomResource directly. The companion consumer guard lives in the admin UI
// (services/[name]/sbom route). The endpoint reads a baked bom.json from a
// config-provided path and 404s with {"status":"not_generated"} when absent.

import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

class SbomResourceTest {

    private val sampleBom = """{"bomFormat":"CycloneDX","specVersion":"1.5","components":[]}"""

    @Test
    fun `returns 200 with the baked CycloneDX body when the file exists`(@TempDir dir: Path) {
        val bom = dir.resolve("bom.json")
        Files.writeString(bom, sampleBom)

        val resource = SbomResource(Optional.of(bom.toString()))
        val response = resource.sbom()

        assertThat(response.status).isEqualTo(Response.Status.OK.statusCode)
        assertThat(String(response.entity as ByteArray)).isEqualTo(sampleBom)
        assertThat(response.getHeaderString("X-Sbom-Schema")).isEqualTo("openbank.sbom.v1")
    }

    @Test
    fun `returns 404 not_generated when the file is absent`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist.json")

        val resource = SbomResource(Optional.of(missing.toString()))
        val response = resource.sbom()

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        assertThat(body["status"]).isEqualTo("not_generated")
    }

    @Test
    fun `returns 404 when the configured path is empty`() {
        val resource = SbomResource(Optional.of(""))
        val response = resource.sbom()

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
    }

    @Test
    fun `falls back to the default path when config is absent`() {
        // Optional.empty() → DEFAULT_PATH (/app/sbom/bom.json), which does not exist
        // in the test sandbox → 404. Verifies the defaulting branch doesn't throw.
        val resource = SbomResource(Optional.empty())
        val response = resource.sbom()

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
    }
}
