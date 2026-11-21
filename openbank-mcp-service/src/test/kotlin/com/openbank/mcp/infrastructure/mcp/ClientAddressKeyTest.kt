// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

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
        assertThat(ClientAddressKey.of("0:0:0:0:0:ffff:c633:6401")).isEqualTo(ClientAddressKey.of("198.51.100.1"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "1.2.3.4:443", "dead:beef", "1::2::3", "[::1", "::1]", "[1.2.3.4]", "localhost", "evil.example",
            "1.2.3.4.5", "01.2.3.4", "256.1.1.1", "1:2:3:4:5:6:7:8:9", "12345::1", "::ffff:1.2.3", "", " ",
            "1:2:3:4:5:6:7::8:9",
        ],
    )
    fun `anything that is not a strict IP literal keys as unknown`(input: String) {
        assertThat(IpLiteral.parse(input)).isNull()
        assertThat(ClientAddressKey.of(input)).isEqualTo(ClientAddressKey.UNKNOWN)
    }

    @Test
    fun `valid literal forms parse`() {
        listOf("::", "::1", "1::", "2001:db8::1", "1:2:3:4:5:6:7:8", "::1.2.3.4", "fe80::1%eth0", "0.0.0.0")
            .forEach { assertThat(IpLiteral.parse(it)).describedAs(it).isNotNull() }
        assertThat(IpLiteral.parse("::ffff:198.51.100.1")).hasSize(4)
        assertThat(ClientAddressKey.of(null)).isEqualTo(ClientAddressKey.UNKNOWN)
    }

    @Test
    fun `the key is a keyed MAC, not a plain address hash`() {
        val plain = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(4, 198.toByte(), 51, 100, 1)),
        )
        assertThat(
            ClientAddressKey.of("198.51.100.1"),
        ).startsWith("ip:").doesNotContain("198.51").isNotEqualTo("ip:$plain")
    }
}

class ProxyForwardingGuardTest {
    private fun guard(props: Map<String, String>) = ProxyForwardingGuard(
        SmallRyeConfigBuilder().withSources(PropertiesConfigSource(props, "test", 500)).build(),
    )

    private val pinned = mapOf(
        ProxyForwardingGuard.EDGE_HEADER to "X-Envoy-External-Address",
        ProxyForwardingGuard.GATEWAY_IDENTITY to "edge-gateway.envoy-gateway-system.client.open-bank.tech",
        ProxyForwardingGuard.CLIENT_AUTH to "required",
    )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "quarkus.http.proxy.proxy-address-forwarding", "quarkus.http.proxy.allow-forwarded",
            "quarkus.http.proxy.allow-x-forwarded", "quarkus.http.proxy.enable-forwarded-host",
            "quarkus.http.proxy.enable-forwarded-prefix", "quarkus.http.proxy.enable-trusted-proxy-header",
            "quarkus.http.proxy.use-proxy-protocol",
        ],
    )
    fun `any Quarkus forwarding switch refuses to boot, even with a narrow trusted list`(switch: String) {
        assertThatThrownBy { guard(mapOf(switch to "true")) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            guard(
                pinned + mapOf(switch to "true", ProxyForwardingGuard.TRUSTED_PROXIES to "10.80.4.0/22"),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a trusted-proxies list refuses to boot on its own - the edge is trusted by identity, not IP`() {
        assertThatThrownBy { guard(mapOf(ProxyForwardingGuard.TRUSTED_PROXIES to "10.80.4.0/22")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the full pinned mTLS shape boots, and so does nothing configured`() {
        assertThatCode { guard(pinned) }.doesNotThrowAnyException()
        assertThatCode { guard(emptyMap()) }.doesNotThrowAnyException()
    }

    /** Removal test: drop ANY one element of the pinned shape and boot is refused. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            ProxyForwardingGuard.EDGE_HEADER,
            ProxyForwardingGuard.GATEWAY_IDENTITY,
            ProxyForwardingGuard.CLIENT_AUTH,
        ],
    )
    fun `removing one element of the pinned mTLS shape refuses to boot`(removed: String) {
        assertThatThrownBy { guard(pinned - removed) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the customer channel without client auth REQUIRED refuses to boot`() {
        val on = mapOf(ProxyForwardingGuard.CUSTOMER_ENABLED to "true")
        assertThatThrownBy { guard(on) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { guard(on + (ProxyForwardingGuard.CLIENT_AUTH to "request")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatCode { guard(on + (ProxyForwardingGuard.CLIENT_AUTH to "required")) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "request", "NONE", ""])
    fun `the edge header with client auth not REQUIRED refuses to boot`(clientAuth: String) {
        assertThatThrownBy { guard(pinned + (ProxyForwardingGuard.CLIENT_AUTH to clientAuth)) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}

class ClientAddressResolverTest {
    private val gatewaySan = "edge-gateway.envoy-gateway-system.client.open-bank.tech"
    private val resolver = ClientAddressResolver(Optional.of("X-Envoy-External-Address"), Optional.of(gatewaySan))

    @Test
    fun `the edge header is used only for a request from the gateway identity`() {
        assertThat(resolver.resolve("10.80.5.7", listOf("198.51.100.9"), fromGateway = true)).isEqualTo("198.51.100.9")
        assertThat(resolver.resolve("10.80.5.7", listOf("198.51.100.9"), fromGateway = false)).isEqualTo("10.80.5.7")
    }

    @Test
    fun `two header values, a list, a hostname or garbage falls back to the peer`() {
        listOf(
            listOf("203.0.113.66", "198.51.100.9"),
            listOf("203.0.113.66, 198.51.100.9"),
            listOf("evil.example"),
            listOf("1.2.3.4:443"),
        )
            .forEach { assertThat(resolver.resolve("10.80.5.7", it, fromGateway = true)).isEqualTo("10.80.5.7") }
        assertThat(resolver.resolve("10.80.5.7", null, fromGateway = true)).isEqualTo("10.80.5.7")
    }

    @Test
    fun `with no edge header configured no header is ever read`() {
        val off = ClientAddressResolver(Optional.empty(), Optional.of(gatewaySan))
        assertThat(off.resolve("10.80.5.7", listOf("198.51.100.9"), fromGateway = true)).isEqualTo("10.80.5.7")
    }

    @Test
    fun `distinct subject-less keys are capped per minute - the N+1th 64 shares the overflow key`() {
        val r = ClientAddressResolver(Optional.empty(), Optional.empty()).apply { maxKeysPerMinute = 3 }
        val now = 1_000_000L * 60_000L
        val keys = (1..4).map { r.capped(ClientAddressKey.of("2001:db8:$it::1"), now) }
        assertThat(keys.take(3)).doesNotHaveDuplicates().doesNotContain(ClientAddressResolver.OVERFLOW)
        assertThat(keys[3]).isEqualTo(ClientAddressResolver.OVERFLOW)
        // an already-admitted key keeps its own window; the next minute starts fresh
        assertThat(r.capped(keys[0], now)).isEqualTo(keys[0])
        assertThat(
            r.capped(ClientAddressKey.of("2001:db8:4::1"), now + 60_000L),
        ).isNotEqualTo(ClientAddressResolver.OVERFLOW)
    }
}
