// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpPathCanonicalFilterTest {
    @Test
    fun `canonical paths pass`() {
        listOf("/", "/mcp", "/mcp/", "/customer/mcp", "/api/v1/mcp/sessions/abc-1", "/q/health/ready")
            .forEach { assertThat(McpPathCanonicalFilter.isCanonical(it)).describedAs(it).isTrue() }
    }

    @Test
    fun `non-canonical paths are refused`() {
        listOf(
            "/customer/mcp;a=b", "/customer/%6dcp", "/customer%2fmcp", "/customer/%2E/mcp", "/customer/./mcp",
            "/x/../customer/mcp", "/customer//mcp", "/customer/mcp/..", "/mcp\\x", "mcp", "",
        ).forEach { assertThat(McpPathCanonicalFilter.isCanonical(it)).describedAs(it).isFalse() }
    }
}
