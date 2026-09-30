// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetAddress

class EgressPolicyTest {
    private val policy =
        EgressPolicy.fromConfig(
            listOf(
                "api.example.com",
                "alt.example.com:443|8443",
                "dev.example.com;http",
                "vault.internal.example;private",
            ),
        )

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("urlBypasses")
    fun `every URL-shaped bypass is rejected before DNS`(url: String, expected: EgressDenialReason) {
        val decision = policy.checkUrl(url)
        assertThat(decision).isInstanceOf(EgressDecision.Denied::class.java)
        assertThat((decision as EgressDecision.Denied).reason).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allowedUrls")
    fun `allowlisted URLs pass the static check`(url: String) {
        assertThat(policy.checkUrl(url)).isInstanceOf(EgressDecision.Allowed::class.java)
    }

    @ParameterizedTest(name = "[{index}] {0} is {1}")
    @MethodSource("addresses")
    fun `resolved addresses are classified`(literal: String, expected: EgressAddressClass) {
        assertThat(EgressAddressClass.of(InetAddress.getByName(literal))).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] api.example.com -> {0} denied")
    @MethodSource("forbiddenResolutions")
    fun `a name resolving to a non-public address is denied`(literal: String) {
        val target = allowed("https://api.example.com/")
        val decision = policy.checkResolved(target, listOf(InetAddress.getByName(literal)))
        assertThat(decision).isInstanceOf(EgressDecision.Denied::class.java)
        assertThat(
            (decision as EgressDecision.Denied).reason,
        ).isEqualTo(EgressDenialReason.RESOLVED_TO_FORBIDDEN_ADDRESS)
    }

    @Test
    fun `a mixed public and private answer is denied`() {
        val target = allowed("https://api.example.com/")
        val decision =
            policy.checkResolved(
                target,
                listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")),
            )
        assertThat(decision).isInstanceOf(EgressDecision.Denied::class.java)
    }

    @Test
    fun `an empty answer is denied`() {
        val decision = policy.checkResolved(allowed("https://api.example.com/"), emptyList())
        assertThat((decision as EgressDecision.Denied).reason).isEqualTo(EgressDenialReason.UNRESOLVABLE)
    }

    @Test
    fun `a public answer is allowed`() {
        val decision = policy.checkResolved(
            allowed("https://api.example.com/"),
            listOf(InetAddress.getByName("93.184.216.34")),
        )
        assertThat(decision).isInstanceOf(EgressDecision.Allowed::class.java)
    }

    @Test
    fun `the private exemption covers private and loopback but never metadata or link-local`() {
        val target = allowed("https://vault.internal.example/")
        assertThat(
            policy.checkResolved(target, listOf(InetAddress.getByName("10.1.2.3"))),
        ).isInstanceOf(EgressDecision.Allowed::class.java)
        assertThat(
            policy.checkResolved(target, listOf(InetAddress.getByName("127.0.0.1"))),
        ).isInstanceOf(EgressDecision.Allowed::class.java)
        listOf("169.254.169.254", "169.254.1.1", "fd00:ec2::254", "fe80::1", "0.0.0.0").forEach {
            assertThat(policy.checkResolved(target, listOf(InetAddress.getByName(it))))
                .`as`(it)
                .isInstanceOf(EgressDecision.Denied::class.java)
        }
    }

    @Test
    fun `an IP literal cannot be allowlisted`() {
        assertThatThrownBy {
            EgressPolicy.fromConfig(listOf("169.254.169.254"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Teredo, local-use NAT64 and the 6to4 relay anycast are RESERVED, not PUBLIC`() {
        listOf("2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "64:ff9b:1::a00:1", "192.88.99.1").forEach {
            assertThat(EgressAddressClass.of(InetAddress.getByName(it))).`as`(it).isEqualTo(EgressAddressClass.RESERVED)
        }
        // neighbours stay public
        assertThat(EgressAddressClass.of(InetAddress.getByName("2001:4860::8888"))).isEqualTo(EgressAddressClass.PUBLIC)
        assertThat(EgressAddressClass.of(InetAddress.getByName("192.88.98.1"))).isEqualTo(EgressAddressClass.PUBLIC)
    }

    @Test
    fun `fromConfig rejects entries whose host is not a DNS name`() {
        listOf("[::1]:80", ":443", "bad_host.example", "-lead.example", "a..b.example", "sp ace.example").forEach {
            assertThatThrownBy { EgressPolicy.fromConfig(listOf(it)) }.`as`(it)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the target exposes the absolute FQDN for resolution`() {
        assertThat(allowed("https://api.example.com/").absoluteName).isEqualTo("api.example.com.")
        assertThat(allowed("https://API.example.com./").host).isEqualTo("api.example.com")
    }

    private fun allowed(url: String) = (policy.checkUrl(url) as EgressDecision.Allowed).target

    companion object {
        @JvmStatic
        fun urlBypasses() = listOf(
            Arguments.of("https://evil.example.org/", EgressDenialReason.HOST_NOT_ALLOWLISTED),
            Arguments.of("https://api.example.com.evil.org/", EgressDenialReason.HOST_NOT_ALLOWLISTED),
            Arguments.of("https://evilapi.example.com/", EgressDenialReason.HOST_NOT_ALLOWLISTED),
            Arguments.of("http://api.example.com/", EgressDenialReason.SCHEME_NOT_ALLOWED),
            Arguments.of("ftp://api.example.com/", EgressDenialReason.SCHEME_NOT_ALLOWED),
            Arguments.of("file:///etc/passwd", EgressDenialReason.MALFORMED_URL),
            Arguments.of("gopher://api.example.com/", EgressDenialReason.SCHEME_NOT_ALLOWED),
            Arguments.of("https://api.example.com@evil.example.org/", EgressDenialReason.USERINFO_PRESENT),
            Arguments.of("https://user:pw@api.example.com/", EgressDenialReason.USERINFO_PRESENT),
            Arguments.of("https://api.example.com%40evil.example.org/", EgressDenialReason.MALFORMED_URL),
            Arguments.of("https://api.example.com\\@evil.example.org/", EgressDenialReason.MALFORMED_URL),
            Arguments.of("https://127.0.0.1/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://169.254.169.254/latest/meta-data/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://2130706433/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://0177.0.0.1/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://0x7f.0.0.1/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://0x7f000001/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://127.1/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://[::1]/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://[::ffff:127.0.0.1]/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://[::ffff:a9fe:a9fe]/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://[fd00::1]/", EgressDenialReason.IP_LITERAL),
            Arguments.of("https://api.example.com:8443/", EgressDenialReason.PORT_NOT_ALLOWED),
            Arguments.of("https://api.example.com:22/", EgressDenialReason.PORT_NOT_ALLOWED),
            Arguments.of("https://alt.example.com:9000/", EgressDenialReason.PORT_NOT_ALLOWED),
            Arguments.of("http://dev.example.com:8080/", EgressDenialReason.PORT_NOT_ALLOWED),
            Arguments.of("/relative/path", EgressDenialReason.MALFORMED_URL),
            Arguments.of("https://api_example.com/", EgressDenialReason.MALFORMED_URL),
        )

        @JvmStatic
        fun allowedUrls() = listOf(
            "https://api.example.com/v1/x?y=1",
            "https://API.Example.COM./v1",
            "https://api.example.com:443/",
            "https://alt.example.com:8443/",
            "http://dev.example.com/",
            "https://dev.example.com/",
        )

        @JvmStatic
        fun addresses() = listOf(
            Arguments.of("93.184.216.34", EgressAddressClass.PUBLIC),
            Arguments.of("2606:2800:220:1:248:1893:25c8:1946", EgressAddressClass.PUBLIC),
            Arguments.of("127.0.0.1", EgressAddressClass.LOOPBACK),
            Arguments.of("127.255.255.254", EgressAddressClass.LOOPBACK),
            Arguments.of("::1", EgressAddressClass.LOOPBACK),
            Arguments.of("10.0.0.1", EgressAddressClass.PRIVATE),
            Arguments.of("172.16.0.1", EgressAddressClass.PRIVATE),
            Arguments.of("172.31.255.255", EgressAddressClass.PRIVATE),
            Arguments.of("172.32.0.1", EgressAddressClass.PUBLIC),
            Arguments.of("192.168.1.1", EgressAddressClass.PRIVATE),
            Arguments.of("100.64.0.1", EgressAddressClass.PRIVATE),
            Arguments.of("100.127.255.255", EgressAddressClass.PRIVATE),
            Arguments.of("fd00::1", EgressAddressClass.PRIVATE),
            Arguments.of("fc00::1", EgressAddressClass.PRIVATE),
            Arguments.of("169.254.169.254", EgressAddressClass.METADATA),
            Arguments.of("fd00:ec2::254", EgressAddressClass.METADATA),
            Arguments.of("100.100.100.200", EgressAddressClass.METADATA),
            Arguments.of("169.254.10.10", EgressAddressClass.LINK_LOCAL),
            Arguments.of("fe80::1", EgressAddressClass.LINK_LOCAL),
            Arguments.of("0.0.0.0", EgressAddressClass.UNSPECIFIED),
            Arguments.of("::", EgressAddressClass.UNSPECIFIED),
            Arguments.of("224.0.0.1", EgressAddressClass.MULTICAST),
            Arguments.of("ff02::1", EgressAddressClass.MULTICAST),
            Arguments.of("240.0.0.1", EgressAddressClass.RESERVED),
            Arguments.of("::ffff:127.0.0.1", EgressAddressClass.LOOPBACK),
            Arguments.of("::ffff:169.254.169.254", EgressAddressClass.METADATA),
            Arguments.of("::10.0.0.1", EgressAddressClass.PRIVATE),
            Arguments.of("64:ff9b::a9fe:a9fe", EgressAddressClass.METADATA),
            Arguments.of("2002:7f00:1::", EgressAddressClass.LOOPBACK),
        )

        @JvmStatic
        fun forbiddenResolutions() = listOf(
            "127.0.0.1", "::1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "100.64.0.1", "fd00::1",
            "169.254.169.254", "fd00:ec2::254", "fe80::1", "0.0.0.0", "::ffff:10.0.0.1", "64:ff9b::7f00:1",
        )
    }
}
