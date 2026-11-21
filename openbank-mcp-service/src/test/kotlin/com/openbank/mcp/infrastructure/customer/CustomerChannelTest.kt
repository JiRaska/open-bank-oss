// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/** The boot gate (ADR-0385 D1): with the channel on, a bad allowlist means the bean cannot be built. */
class CustomerChannelTest {

    private fun channel(enabled: Boolean?, issuer: String?, location: String?) = CustomerChannel(
        Optional.ofNullable(enabled),
        Optional.ofNullable(issuer),
        Optional.ofNullable(location),
    )

    @Test
    fun `off by default - absent flag means no admission and no allowlist read`() {
        assertThat(channel(null, null, "classpath:does-not-exist.yaml").enabled).isFalse()
        assertThat(channel(false, null, null).admission).isNull()
    }

    @Test
    fun `enabled with a valid classpath allowlist loads it`() {
        val c = channel(true, DpopTestProofs.ISSUER, "classpath:test-mcp-client-allowlist.yaml")
        assertThat(c.enabled).isTrue()
        assertThat(c.admission!!.allowlist.admitted("mcp-local-test")).isNotNull()
    }

    @Test
    fun `enabled with a missing allowlist refuses to start`() {
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, "classpath:does-not-exist.yaml") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not found")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `enabled with a malformed allowlist refuses to start`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("bad.yaml"), "schema_version: 1\nclients: []\n")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("malformed")
    }

    @Test
    fun `enabled without an issuer refuses to start`() {
        assertThatThrownBy { channel(true, " ", "classpath:test-mcp-client-allowlist.yaml") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("issuer")
    }

    @Test
    fun `an allowlist naming another audience refuses to start`(@TempDir dir: Path) {
        val text = javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText()
            .replace("audience: openbank-mcp-service", "audience: openbank-app")
        val file = Files.writeString(dir.resolve("aud.yaml"), text)
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audience")
    }

    @Test
    fun `a YAML syntax error is a clean boot refusal, not a parser exception`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("broken.yaml"), "schema_version: 1\nclients: [ {client_id: a\n")
        assertThatThrownBy { channel(true, DpopTestProofs.ISSUER, file.toString()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("malformed")
    }
}
