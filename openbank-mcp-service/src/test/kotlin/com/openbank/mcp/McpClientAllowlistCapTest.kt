// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.mcp.infrastructure.observability.McpCustomerClientAllowlist
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

/** ADR-0384 D1: `client` is a metric label only while the allowlist stays at or below 25 entries. */
class McpClientAllowlistCapTest {

    private fun ids(n: Int) = (1..n).map { "client-$it" }

    @Test
    fun `a 25-entry allowlist is accepted`() {
        assertThat(McpCustomerClientAllowlist(Optional.of(ids(25))).clients).hasSize(25)
    }

    @Test
    fun `a 26-entry allowlist refuses to construct, so the bean fails at startup`() {
        assertThatThrownBy { McpCustomerClientAllowlist(Optional.of(ids(26))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("caps it at 25")
    }

    @Test
    fun `an absent allowlist is empty and labels everything unlisted`() {
        val none = McpCustomerClientAllowlist(Optional.empty())
        assertThat(none.label("anything")).isEqualTo("unlisted")
        assertThat(none.label(null)).isEqualTo("unlisted")
    }

    @Test
    fun `an entry that is not a short id is refused, so a raw URL cannot become a label`() {
        assertThatThrownBy { McpCustomerClientAllowlist(listOf("https://client.example/cb")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
