// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Same deployment shape as [McpTrustedProxyForwardingIT], but the test client is NOT in the trust
 * list (only a TEST-NET address is): every forwarded header it sends must be ignored. So the
 * identical admitted-elsewhere request is refused on the tenant's `htu` check (it sees the direct
 * URL, not the published one), and a spoofed `X-Forwarded-For` does not move the audit key (#16847).
 */
@QuarkusTest
@TestProfile(McpUntrustedProxyForwardingIT.UntrustedPeer::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpUntrustedProxyForwardingIT {

    class UntrustedPeer : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = ProxyForwardingSupport.realTenants("192.0.2.1")
    }

    @Test
    fun `forwarded scheme and host from an untrusted peer are ignored, so the DPoP htu does not match`() {
        ProxyForwardingSupport.forwardedToolsList("c0ffee00-0000-0000-0000-000000000012").statusCode(401)
    }

    @Test
    fun `a spoofed X-Forwarded-For from an untrusted peer does not change the audit key`() {
        val direct = ProxyForwardingSupport.addressKey()
        assertThat(ProxyForwardingSupport.addressKey("203.0.113.7")).isEqualTo(direct)
        assertThat(ProxyForwardingSupport.addressKey("2001:db8:1:2::a")).isEqualTo(direct)
    }
}
