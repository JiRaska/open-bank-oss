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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " , ", "0.0.0.0/0", "0.0.0.0/00", "::/0", "::0/0", "0:0:0:0:0:0:0:0/0", "[::]/0",
            "0.0.0.0/1,128.0.0.0/1", "10.0.0.0/7", "2001::/15", "gateway.envoy-gateway-system.svc", "*",
            "10.80.0.0/16,edge.example", "10.0.0.0/33",
        ],
    )
    fun `forwarding with a missing, broad or non-literal trusted list refuses to boot`(list: String) {
        assertThatThrownBy { ProxyForwardingGuard.check(true, list) }.isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "quarkus.http.proxy.proxy-address-forwarding", "quarkus.http.proxy.allow-forwarded",
            "quarkus.http.proxy.allow-x-forwarded", "quarkus.http.proxy.enable-forwarded-host",
            "quarkus.http.proxy.enable-forwarded-prefix", "quarkus.http.proxy.enable-trusted-proxy-header",
            "quarkus.http.proxy.use-proxy-protocol",
        ],
    )
    fun `constructor - any real forwarding switch with no list throws IllegalStateException`(switch: String) {
        assertThatThrownBy { guard(mapOf(switch to "true")) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { guard(mapOf(switch to "true", ProxyForwardingGuard.TRUSTED_PROXIES to "0.0.0.0/0")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatCode { guard(mapOf(switch to "true", ProxyForwardingGuard.TRUSTED_PROXIES to "10.80.4.0/22")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `constructor - the edge header alone requires the list even with forwarding off`() {
        assertThatThrownBy { guard(mapOf(ProxyForwardingGuard.EDGE_HEADER to "X-Envoy-External-Address")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatCode { guard(emptyMap()) }.doesNotThrowAnyException()
    }

    @Test
    fun `narrow literal lists pass`() {
        ProxyForwardingGuard.check(true, "10.80.4.0/22, 2001:db8:ff::/48, 127.0.0.1")
        ProxyForwardingGuard.check(false, "")
    }
}

class ClientAddressResolverTest {
    private val resolver = ClientAddressResolver(
        Optional.of("X-Envoy-External-Address"),
        Optional.of("10.80.4.0/22, 2001:db8:ff::/48"),
    )

    @Test
    fun `the edge header is used only from a peer inside a trusted CIDR`() {
        assertThat(resolver.resolve("10.80.5.7", listOf("198.51.100.9"))).isEqualTo("198.51.100.9")
        assertThat(resolver.resolve("10.80.8.1", listOf("198.51.100.9"))).isEqualTo("10.80.8.1")
        assertThat(resolver.resolve("2001:db8:ff:1::2", listOf("198.51.100.9"))).isEqualTo("198.51.100.9")
        assertThat(resolver.resolve("2001:db8:fe::2", listOf("198.51.100.9"))).isEqualTo("2001:db8:fe::2")
    }

    @Test
    fun `two header values, a list, a hostname or garbage falls back to the peer`() {
        assertThat(resolver.resolve("10.80.5.7", listOf("203.0.113.66", "198.51.100.9"))).isEqualTo("10.80.5.7")
        assertThat(resolver.resolve("10.80.5.7", listOf("203.0.113.66, 198.51.100.9"))).isEqualTo("10.80.5.7")
        assertThat(resolver.resolve("10.80.5.7", listOf("evil.example"))).isEqualTo("10.80.5.7")
        assertThat(resolver.resolve("10.80.5.7", listOf("1.2.3.4:443"))).isEqualTo("10.80.5.7")
        assertThat(resolver.resolve("10.80.5.7", null)).isEqualTo("10.80.5.7")
    }

    @Test
    fun `with no edge header configured no header is ever read`() {
        val off = ClientAddressResolver(Optional.empty(), Optional.of("10.80.0.0/16"))
        assertThat(off.resolve("10.80.5.7", listOf("198.51.100.9"))).isEqualTo("10.80.5.7")
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

    @Test
    fun `one 48 cannot exhaust the global key budget - other networks keep their own key`() {
        val r = ClientAddressResolver(Optional.empty(), Optional.empty()).apply {
            maxKeysPerMinute = 8
            maxKeysPerAllocation = 2
        }
        val now = 1_000_000L * 60_000L
        fun call(address: String) = r.capped(ClientAddressKey.of(address), now, ClientAddressKey.allocationOf(address))
        // one attacker cycles 100 /64s inside 2001:db8:aaaa::/48
        val flood = (1..100).map { call("2001:db8:aaaa:${it.toString(16)}::1") }.toSet()
        assertThat(flood).hasSize(3).doesNotContain(ClientAddressResolver.OVERFLOW)
        assertThat(flood).contains(ClientAddressKey.allocationOf("2001:db8:aaaa::1"))
        // an unrelated caller still gets its own window, not the shared overflow key
        assertThat(call("198.51.100.7")).isEqualTo(ClientAddressKey.of("198.51.100.7"))
        assertThat(call("2001:db8:bbbb:1::1")).isEqualTo(ClientAddressKey.of("2001:db8:bbbb:1::1"))
    }

    @Test
    fun `one 32 cannot exhaust the global key budget - 100 48s inside it leave other callers their own key`() {
        val r = ClientAddressResolver(Optional.empty(), Optional.empty()).apply {
            maxKeysPerMinute = 16
            maxKeysPerAllocation = 2
            maxKeysPerRegion = 4
        }
        val now = 1_000_000L * 60_000L
        fun call(address: String) = r.capped(
            ClientAddressKey.of(address),
            now,
            ClientAddressKey.allocationOf(address),
            ClientAddressKey.regionOf(address),
        )
        // one attacker spreads over 100 /48s (two /64s each) inside 2001:db8::/32
        val flood = (1..100).flatMap { n ->
            val h = n.toString(16)
            listOf(call("2001:db8:$h:1::1"), call("2001:db8:$h:2::1"))
        }.toSet()
        assertThat(flood).hasSize(5).doesNotContain(ClientAddressResolver.OVERFLOW)
        assertThat(flood).contains(ClientAddressKey.regionOf("2001:db8::1"))
        assertThat(call("198.51.100.7")).isEqualTo(ClientAddressKey.of("198.51.100.7"))
        assertThat(call("2001:db9:1:1::1")).isEqualTo(ClientAddressKey.of("2001:db9:1:1::1"))
        assertThat(r.overflowWarnings).isZero()
    }

    @Test
    fun `the overflow WARN is written once per minute`() {
        val r = ClientAddressResolver(Optional.empty(), Optional.empty()).apply { maxKeysPerMinute = 2 }
        val now = 1_000_000L * 60_000L
        (1..10).forEach { r.capped(ClientAddressKey.of("198.51.$it.1"), now) }
        assertThat(r.overflowWarnings).isEqualTo(1)
        (1..10).forEach { r.capped(ClientAddressKey.of("203.0.$it.1"), now + 1_000L) }
        assertThat(r.overflowWarnings).isEqualTo(1)
        (1..10).forEach { r.capped(ClientAddressKey.of("192.0.$it.1"), now + 60_000L) }
        assertThat(r.overflowWarnings).isEqualTo(2)
    }

    @Test
    fun `ipv4 allocation is the 24 and differs from the address key`() {
        assertThat(
            ClientAddressKey.allocationOf("198.51.100.7"),
        ).isEqualTo(ClientAddressKey.allocationOf("198.51.100.200"))
        assertThat(
            ClientAddressKey.allocationOf("198.51.100.7"),
        ).isNotEqualTo(ClientAddressKey.allocationOf("198.51.101.7"))
        assertThat(ClientAddressKey.allocationOf("198.51.100.7")).isNotEqualTo(ClientAddressKey.of("198.51.100.7"))
    }
}
