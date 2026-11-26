// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class HostedOAuthSiteConfigurationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `scoped registration is an optional separate projected file`() {
        val site = directory.resolve("site.json")
        Files.writeString(site, "{}")
        val config = HostedOAuthSiteConfiguration.load(site, ObjectMapper())
        assertThat(config.scopedChatGpt()).isNull()
        val fixtureJwks = "fixture public JWKS bytes".encodeToByteArray()
        val encoded = Base64.getEncoder().encodeToString(fixtureJwks)
        Files.writeString(
            directory.resolve("chatgpt-scoped.json"),
            """
            {
              "clientId": "https://chatgpt.com/oauth/fixture/client.json",
              "callback": "https://chatgpt.com/connector/oauth/fixture",
              "jwksBase64": "$encoded",
              "jwksSha256": "fixture-digest"
            }
            """.trimIndent(),
        )
        val scoped = checkNotNull(config.scopedChatGpt())
        assertThat(scoped.registration.clientId).isEqualTo("https://chatgpt.com/oauth/fixture/client.json")
        assertThat(scoped.registration.callback).isEqualTo("https://chatgpt.com/connector/oauth/fixture")
        assertThat(scoped.jwks).isEqualTo(fixtureJwks)
        assertThat(Files.readString(site)).isEqualTo("{}")
    }
}
