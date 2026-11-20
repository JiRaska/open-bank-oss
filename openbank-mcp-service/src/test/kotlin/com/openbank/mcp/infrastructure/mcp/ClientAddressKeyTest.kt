// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClientAddressKeyTest {
    @Test
    fun `IPv4 is keyed by the full address`() {
        assertThat(ClientAddressKey.of("198.51.100.1")).isNotEqualTo(ClientAddressKey.of("198.51.100.2"))
        assertThat(ClientAddressKey.of("198.51.100.1")).isEqualTo(ClientAddressKey.of("198.51.100.1"))
    }

    @Test
    fun `IPv6 is keyed by its 64 prefix`() {
        val a = ClientAddressKey.of("2001:db8:aa:bb::1")
        assertThat(a).isEqualTo(ClientAddressKey.of("2001:db8:aa:bb:ffff:ffff:ffff:ffff"))
        assertThat(a).isEqualTo(ClientAddressKey.of("[2001:db8:aa:bb::9]"))
        assertThat(a).isNotEqualTo(ClientAddressKey.of("2001:db8:aa:bc::1"))
    }

    @Test
    fun `an IPv4-mapped IPv6 address keys as its IPv4 address`() {
        assertThat(ClientAddressKey.of("::ffff:198.51.100.1")).isEqualTo(ClientAddressKey.of("198.51.100.1"))
    }

    @Test
    fun `a hostname, null or garbage is never resolved and keys as unknown`() {
        assertThat(ClientAddressKey.of(null)).isEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(ClientAddressKey.of("localhost")).isEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(ClientAddressKey.of("evil.example")).isEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(ClientAddressKey.of("1.2.3.4.5")).isEqualTo(ClientAddressKey.UNKNOWN)
        assertThat(ClientAddressKey.of("")).isEqualTo(ClientAddressKey.UNKNOWN)
    }

    @Test
    fun `the key does not carry the address in clear`() {
        assertThat(ClientAddressKey.of("198.51.100.1")).startsWith("ip:").doesNotContain("198.51")
    }

    @Test
    fun `forwarding with no trusted-proxy list or a trust-all list refuses to boot`() {
        assertThatThrownBy { ProxyForwardingGuard.check(true, "") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { ProxyForwardingGuard.check(true, " , ") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { ProxyForwardingGuard.check(true, "10.0.0.0/8,0.0.0.0/0") }
            .isInstanceOf(IllegalStateException::class.java)
        ProxyForwardingGuard.check(true, "10.80.0.0/16")
        ProxyForwardingGuard.check(false, "")
    }
}
