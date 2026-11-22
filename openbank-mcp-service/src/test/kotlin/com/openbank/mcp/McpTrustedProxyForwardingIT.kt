// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.mcp.ClientAddressKey
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * The hop IS a trusted proxy (loopback is the trust list): the edge's forwarded scheme/host reach
 * the customers tenant's own DPoP `htu` check, and the forwarded client address reaches the audit
 * key, IPv6 grouped by /64 (#16847). Counterpart: [McpUntrustedProxyForwardingIT].
 */
@QuarkusTest
@TestProfile(McpTrustedProxyForwardingIT.TrustedLoopback::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpTrustedProxyForwardingIT {

    class TrustedLoopback : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            ProxyForwardingSupport.realTenants("127.0.0.1,0:0:0:0:0:0:0:1")
    }

    @Test
    fun `a real-tenant DPoP proof for the published URL is admitted when the trusted edge forwards scheme and host`() {
        ProxyForwardingSupport.forwardedToolsList("c0ffee00-0000-0000-0000-000000000011")
            .statusCode(200)
            .body("result.tools.size()", equalTo(0))
    }

    @Test
    fun `a client-supplied X-Forwarded-For passed through an APPENDING trusted proxy never becomes the key`() {
        val direct = ProxyForwardingSupport.addressKey()
        // Not vacuous: Quarkus' own forwarding DOES take the forged leftmost entry from this trusted hop.
        assertThat(ProxyForwardingSupport.forwardedRemote("203.0.113.66, 198.51.100.9")).isEqualTo("203.0.113.66")
        // What an appending edge sends: the client's forged entry first, the real client after it.
        // Quarkus' own forwarding would take the leftmost; the audit key must not.
        assertThat(ProxyForwardingSupport.addressKey(forwardedFor = "203.0.113.66, 198.51.100.9")).isEqualTo(direct)
        assertThat(ProxyForwardingSupport.addressKey(forwardedFor = "203.0.113.66")).isEqualTo(direct)
    }

    @Test
    fun `the single edge-set client header from a trusted proxy becomes the key`() {
        assertThat(ProxyForwardingSupport.addressKey(edge = listOf("198.51.100.9")))
            .isEqualTo(ClientAddressKey.of("198.51.100.9"))
            .isNotEqualTo(ProxyForwardingSupport.addressKey())
        // A list, or the header sent twice, is not a single edge-set value: ignored.
        assertThat(ProxyForwardingSupport.addressKey(edge = listOf("203.0.113.66, 198.51.100.9")))
            .isEqualTo(ProxyForwardingSupport.addressKey())
        assertThat(ProxyForwardingSupport.addressKey(edge = listOf("203.0.113.66", "198.51.100.9")))
            .isEqualTo(ProxyForwardingSupport.addressKey())
    }

    @Test
    fun `IPv6 clients rotating inside one 64 share one key, another 64 does not`() {
        val a = ProxyForwardingSupport.addressKey(edge = listOf("2001:db8:1:2::a"))
        val b = ProxyForwardingSupport.addressKey(edge = listOf("2001:db8:1:2:ffff:eeee:dddd:b"))
        val other = ProxyForwardingSupport.addressKey(edge = listOf("2001:db8:1:3::a"))
        assertThat(a).isEqualTo(b).isEqualTo(ClientAddressKey.of("2001:db8:1:2::1")).isNotEqualTo(other)
        assertThat(a).isNotEqualTo(ClientAddressKey.UNKNOWN)
    }
}
