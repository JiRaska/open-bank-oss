// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * The tokenless `ip:` refusal source of the customer channel (channel-off refusals in [McpEndpoint],
 * subject-less refusals in [McpCustomerBranch]) is [ClientAddressKey] over the address
 * [ClientAddressResolver] picks — never a hash of the raw peer string (#16919 adoption, #16800).
 */
class TokenlessRefusalSourceTest {
    private val untrustedEdge = ClientAddressResolver(Optional.of(EDGE), Optional.of("10.80.0.0/16"))

    private fun source(peer: String, edge: List<String> = emptyList()) =
        tokenlessRefusalSource { ClientAddressKey.of(untrustedEdge.resolve(peer, edge)) }

    @Test
    fun `addresses rotating inside one IPv6 64 share a source, another 64 does not`() {
        val first = source("2001:db8:aa:bb::1")
        assertThat(first).startsWith("ip:").isNotEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(source("2001:db8:aa:bb:ffff:1:2:3")).isEqualTo(first)
        assertThat(source("2001:db8:aa:bb::9999")).isEqualTo(first)
        assertThat(source("2001:db8:aa:bc::1")).isNotEqualTo(first)
    }

    @Test
    fun `a 64 rotation shares ONE audit window - the rotated call is suppressed`() {
        val limiter = McpRateLimiter(true, 60, 1000, 10, 4)
        val prefix = CUSTOMER_REFUSAL_KEY_PREFIX
        assertThat(limiter.shouldAuditThrottle(prefix + source("2001:db8:aa:bb::1"))).isTrue()
        assertThat(limiter.shouldAuditThrottle(prefix + source("2001:db8:aa:bb::2"))).isFalse()
        // control: a different network still gets its own row
        assertThat(limiter.shouldAuditThrottle(prefix + source("2001:db8:cc:dd::1"))).isTrue()
    }

    @Test
    fun `a spoofed forwarded address from an untrusted peer does not change the source`() {
        val direct = source("198.51.100.20")
        assertThat(source("198.51.100.20", listOf("203.0.113.7"))).isEqualTo(direct)
        assertThat(source("198.51.100.20", listOf("2001:db8:1:2::a"))).isEqualTo(direct)
        // control: the same header from a TRUSTED peer does move it, so the assertion above can fail
        assertThat(source("10.80.1.1", listOf("203.0.113.7"))).isNotEqualTo(source("10.80.1.1"))
    }

    @Test
    fun `an unresolvable client keys as unknown, never as a fresh per-call key`() {
        assertThat(tokenlessRefusalSource { error("no request in scope") }).isEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(customerRefusalWindowKey(null) { error("boom") })
            .isEqualTo(CUSTOMER_REFUSAL_KEY_PREFIX + ClientAddressKey.UNKNOWN)
        assertThat(customerRefusalWindowKey("s-1") { error("never called") })
            .isEqualTo(CUSTOMER_REFUSAL_KEY_PREFIX + "sub:s-1")
    }

    private companion object {
        const val EDGE = "X-Envoy-External-Address"
    }
}
