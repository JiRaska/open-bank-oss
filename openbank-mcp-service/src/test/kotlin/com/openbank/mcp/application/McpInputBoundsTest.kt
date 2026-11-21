// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class McpInputBoundsTest {

    @Test
    fun `a valid canonical IBAN passes`() {
        assertThat(McpInputBounds.requireIban(VALID)).isEqualTo(VALID)
    }

    @Test
    fun `a lowercase or spaced form of a valid IBAN is refused, not normalised`() {
        listOf(VALID.lowercase(), "CZ65 0800 0000 1920 0014 5399", " $VALID", "$VALID ").forEach {
            assertThatThrownBy { McpInputBounds.requireIban(it) }
                .`as`(it).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a wrong checksum is refused`() {
        assertThatThrownBy { McpInputBounds.requireIban("CZ6608000000192000145399") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `limit is bounded to 1 to 200 with a default`() {
        val m = jacksonObjectMapper()
        assertThat(McpInputBounds.transactionLimit(m.missingNode())).isEqualTo(50)
        assertThat(McpInputBounds.transactionLimit(m.readTree("200"))).isEqualTo(200)
        listOf("0", "201", "1.5", "\"10\"").forEach {
            assertThatThrownBy { McpInputBounds.transactionLimit(m.readTree(it)) }
                .`as`(it).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `logSafe bounds the value and strips NUL and control characters`() {
        val hostile = "tools/call\u0000\r\nFORGED log line\u001b[31m" + "x".repeat(10_000)
        val safe = McpInputBounds.logSafe(hostile)
        assertThat(safe.length).isLessThanOrEqualTo(McpInputBounds.MAX_ECHO_CHARS + "...[truncated]".length)
        assertThat(safe).doesNotContain("\u0000", "\r", "\n", "\u001b").startsWith("tools/call???FORGED")
        assertThat(McpInputBounds.logSafe("tools/call")).isEqualTo("tools/call")
    }

    private companion object {
        const val VALID = "CZ6508000000192000145399"
    }
}
