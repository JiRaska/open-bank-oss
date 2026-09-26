// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * Outbound-HTTP (SSRF) policy — ADR-0320 P1.
 *
 * Pure decision logic, no I/O: the runtime `SafeHttpClient` resolves DNS itself and hands the
 * resolved addresses back to [checkResolved], then connects to exactly the address that was
 * vetted. Two checks, in order:
 *
 * 1. [checkUrl] — the URL as written: parseable, no userinfo, scheme `https` (or `http` only for
 *    a host whose rule opts in), host is a DNS name (never an IP literal in any encoding), host is
 *    on the allowlist, port is one the rule permits.
 * 2. [checkResolved] — EVERY address the name resolved to must be public, or (for a host whose
 *    rule sets [EgressHostRule.allowPrivateAddresses]) private/loopback. Link-local, cloud
 *    metadata, unspecified, multicast and reserved ranges are never permitted, whatever the rule.
 *
 * @property rules allowlist keyed by lower-case host name, without a trailing dot.
 */
class EgressPolicy(rules: Map<String, EgressHostRule>) {
    val rules: Map<String, EgressHostRule> = rules.mapKeys { normaliseHost(it.key) }

    init {
        this.rules.keys.forEach { host ->
            require(!looksLikeIpLiteral(host)) {
                "egress allowlist entry '$host' is an IP literal; allowlist host names only"
            }
        }
    }

    /** Static URL check. Performs no DNS resolution. One early return per bypass class, kept flat on purpose. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun checkUrl(url: String): EgressDecision {
        val uri =
            try {
                URI(url)
            } catch (e: URISyntaxException) {
                return EgressDecision.Denied(EgressDenialReason.MALFORMED_URL, e.reason ?: "unparseable")
            }
        if (!uri.isAbsolute || uri.isOpaque) {
            return EgressDecision.Denied(EgressDenialReason.MALFORMED_URL, "not an absolute hierarchical URL")
        }
        val rawAuthority = uri.rawAuthority
        if (uri.rawUserInfo != null || (rawAuthority != null && rawAuthority.contains('@'))) {
            return EgressDecision.Denied(EgressDenialReason.USERINFO_PRESENT, "userinfo in URL authority")
        }
        val rawHost = uri.host
        if (rawHost == null) {
            // java.net.URI refuses some numeric hosts (`0x7f.0.0.1`, `127.1`) as server-based
            // authorities; they are still IP literals to libc, so name them as such.
            val authorityHost = rawAuthority?.substringBeforeLast(':')?.let(::normaliseHost)
            return if (authorityHost != null && looksLikeIpLiteral(authorityHost)) {
                EgressDecision.Denied(EgressDenialReason.IP_LITERAL, authorityHost)
            } else {
                EgressDecision.Denied(EgressDenialReason.MALFORMED_URL, "no server-based host")
            }
        }
        if (rawHost.startsWith("[") || looksLikeIpLiteral(normaliseHost(rawHost))) {
            return EgressDecision.Denied(EgressDenialReason.IP_LITERAL, rawHost)
        }
        if (rawHost.any { it.code > ASCII_MAX_PRINTABLE || it.code < ASCII_MIN_PRINTABLE }) {
            return EgressDecision.Denied(EgressDenialReason.MALFORMED_URL, "non-ASCII host")
        }
        val host = normaliseHost(rawHost)
        val rule = rules[host]
            ?: return EgressDecision.Denied(EgressDenialReason.HOST_NOT_ALLOWLISTED, host)
        val scheme = uri.scheme.lowercase()
        when (scheme) {
            "https" -> Unit
            "http" ->
                if (!rule.allowPlainHttp) {
                    return EgressDecision.Denied(EgressDenialReason.SCHEME_NOT_ALLOWED, "http not enabled for $host")
                }
            else -> return EgressDecision.Denied(EgressDenialReason.SCHEME_NOT_ALLOWED, scheme)
        }
        val port = if (uri.port == -1) defaultPort(scheme) else uri.port
        val allowedPorts = rule.ports.ifEmpty { setOf(defaultPort(scheme)) }
        if (port !in allowedPorts) {
            return EgressDecision.Denied(EgressDenialReason.PORT_NOT_ALLOWED, "$host:$port")
        }
        return EgressDecision.Allowed(EgressTarget(scheme = scheme, host = host, port = port, uri = uri, rule = rule))
    }

    /**
     * Resolved-address check. Denies if the name resolved to nothing, or if ANY address is not
     * permitted — a mixed public/private answer is a rebinding shape, not a choice to make.
     */
    fun checkResolved(target: EgressTarget, addresses: List<InetAddress>): EgressDecision {
        if (addresses.isEmpty()) {
            return EgressDecision.Denied(EgressDenialReason.UNRESOLVABLE, target.host)
        }
        for (address in addresses) {
            val cls = EgressAddressClass.of(address)
            val ok =
                cls == EgressAddressClass.PUBLIC ||
                    (target.rule.allowPrivateAddresses && cls in EXEMPTABLE)
            if (!ok) {
                return EgressDecision.Denied(
                    EgressDenialReason.RESOLVED_TO_FORBIDDEN_ADDRESS,
                    "${target.host} -> ${address.hostAddress} ($cls)",
                )
            }
        }
        return EgressDecision.Allowed(target)
    }

    companion object {
        private const val ASCII_MIN_PRINTABLE = 0x21
        private const val ASCII_MAX_PRINTABLE = 0x7e
        private const val MAX_PORT = 65535
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private val EXEMPTABLE = setOf(EgressAddressClass.PRIVATE, EgressAddressClass.LOOPBACK)

        /**
         * Builds a policy from config entries of the form `host[:port|port...][;http][;private]`,
         * e.g. `api.example.com`, `openbao.vault.svc:8200;http;private`. Services read the list
         * with their own `@ConfigProperty(name = "openbank.egress.allowed-hosts")`.
         */
        fun fromConfig(entries: List<String>): EgressPolicy {
            val rules = mutableMapOf<String, EgressHostRule>()
            entries.map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val parts = entry.split(';').map { it.trim().lowercase() }
                val hostPort = parts.first()
                val flags = parts.drop(1).toSet()
                require(flags.all { it in setOf("http", "private") }) { "unknown egress flag in '$entry'" }
                val host = normaliseHost(hostPort.substringBefore(':'))
                require(isDnsName(host)) { "egress allowlist entry '$entry' does not name a valid DNS host" }
                val ports =
                    if (':' in hostPort) {
                        hostPort.substringAfter(':').split('|').map { p ->
                            requireNotNull(p.toIntOrNull()?.takeIf { it in 1..MAX_PORT }) { "bad port in '$entry'" }
                        }.toSet()
                    } else {
                        emptySet()
                    }
                rules[host] =
                    EgressHostRule(
                        ports = ports,
                        allowPlainHttp = "http" in flags,
                        allowPrivateAddresses =
                        "private" in flags,
                    )
            }
            return EgressPolicy(rules)
        }

        internal fun normaliseHost(host: String): String = host.lowercase().removeSuffix(".")

        private const val MAX_DNS_NAME = 253
        private val DNS_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** LDH host name (RFC 1123): 1..63-char labels of letters, digits and inner hyphens. */
        internal fun isDnsName(host: String): Boolean =
            host.isNotEmpty() && host.length <= MAX_DNS_NAME && host.split('.').all { DNS_LABEL.matches(it) }

        private fun defaultPort(scheme: String): Int = if (scheme == "http") HTTP_PORT else HTTPS_PORT

        /**
         * True for anything an IPv4 parser (inet_aton, which libc and many resolvers accept)
         * could read as an address: dotted, dotless decimal (127.0.0.1 written as one 32-bit integer), octal (`0177.0.0.1`),
         * hex (`0x7f.1`), short forms (`127.1`); and anything containing ':' (IPv6).
         * A real DNS name's last label is never all-numeric, so this does not over-match.
         */
        internal fun looksLikeIpLiteral(host: String): Boolean {
            if (host.contains(':')) return true
            val labels = host.split('.')
            if (labels.isEmpty()) return false
            val numeric = Regex("^(0x[0-9a-f]*|[0-9]+)$")
            return numeric.matches(labels.last())
        }
    }
}

/** Per-host allowance. Empty [ports] means the scheme's default port only. */
data class EgressHostRule(
    val ports: Set<Int> = emptySet(),
    val allowPlainHttp: Boolean = false,
    val allowPrivateAddresses: Boolean = false,
)

data class EgressTarget(val scheme: String, val host: String, val port: Int, val uri: URI, val rule: EgressHostRule) {
    /**
     * [host] as an absolute FQDN (trailing dot). Resolve THIS, never [host]: in Kubernetes a
     * relative name with fewer than `ndots` dots is first tried against every search domain, so
     * `api.example.com` could resolve to an in-cluster `api.example.com.<ns>.svc.cluster.local`
     * Service. SNI, certificate identity and the `Host` header keep using [host].
     */
    val absoluteName: String get() = "$host."
}

sealed interface EgressDecision {
    data class Allowed(val target: EgressTarget) : EgressDecision

    data class Denied(val reason: EgressDenialReason, val detail: String) : EgressDecision
}

enum class EgressDenialReason {
    MALFORMED_URL,
    USERINFO_PRESENT,
    IP_LITERAL,
    HOST_NOT_ALLOWLISTED,
    SCHEME_NOT_ALLOWED,
    PORT_NOT_ALLOWED,
    UNRESOLVABLE,
    RESOLVED_TO_FORBIDDEN_ADDRESS,
}

/** Classification of a resolved address. Only [PUBLIC] is reachable without an explicit exemption. */
enum class EgressAddressClass {
    PUBLIC,
    PRIVATE,
    LOOPBACK,
    LINK_LOCAL,
    METADATA,
    UNSPECIFIED,
    MULTICAST,
    RESERVED,
    ;

    companion object {
        fun of(address: InetAddress): EgressAddressClass = when (address) {
            is Inet4Address -> ofV4(address.address)
            is Inet6Address -> ofV6(address.address)
            else -> RESERVED
        }

        @Suppress("CyclomaticComplexMethod", "MagicNumber")
        private fun ofV4(b: ByteArray): EgressAddressClass {
            val o = b.map { it.toInt() and 0xff }
            return when {
                o == listOf(169, 254, 169, 254) || o == listOf(100, 100, 100, 200) -> METADATA
                o.all { it == 0 } -> UNSPECIFIED
                o[0] == 0 -> RESERVED
                o[0] == 127 -> LOOPBACK
                o[0] == 10 -> PRIVATE
                o[0] == 172 && o[1] in 16..31 -> PRIVATE
                o[0] == 192 && o[1] == 168 -> PRIVATE
                o[0] == 100 && o[1] in 64..127 -> PRIVATE
                o[0] == 169 && o[1] == 254 -> LINK_LOCAL
                o[0] == 192 && o[1] == 0 && o[2] == 0 -> RESERVED
                o[0] == 192 && o[1] == 0 && o[2] == 2 -> RESERVED
                o[0] == 192 && o[1] == 88 && o[2] == 99 -> RESERVED // deprecated 6to4 relay anycast
                o[0] == 198 && o[1] in 18..19 -> RESERVED
                o[0] == 198 && o[1] == 51 && o[2] == 100 -> RESERVED
                o[0] == 203 && o[1] == 0 && o[2] == 113 -> RESERVED
                o[0] in 224..239 -> MULTICAST
                o[0] >= 240 -> RESERVED
                else -> PUBLIC
            }
        }

        @Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount", "ComplexCondition")
        private fun ofV6(b: ByteArray): EgressAddressClass {
            val u = b.map { it.toInt() and 0xff }
            val first10Zero = (0 until 10).all { u[it] == 0 }
            // IPv4-mapped ::ffff:a.b.c.d (Java usually hands these back as Inet4Address, but an
            // Inet6Address can still carry one) and deprecated IPv4-compatible ::a.b.c.d.
            if (first10Zero && u[10] == 0xff && u[11] == 0xff) return ofV4(b.copyOfRange(12, 16))
            if ((0 until 15).all { u[it] == 0 }) return if (u[15] == 1) LOOPBACK else UNSPECIFIED
            if (first10Zero && u[10] == 0 && u[11] == 0) return ofV4(b.copyOfRange(12, 16))
            // NAT64 64:ff9b::/96 and 6to4 2002::/16 embed an IPv4 address that the gateway reaches.
            if (u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && (4 until 12).all { u[it] == 0 }) {
                return ofV4(b.copyOfRange(12, 16))
            }
            if (u[0] == 0x20 && u[1] == 0x02) return ofV4(b.copyOfRange(2, 6))
            // AWS IMDS over IPv6: fd00:ec2::254
            if (u[0] == 0xfd &&
                u[1] == 0x00 &&
                u[2] == 0x0e &&
                u[3] == 0xc2 &&
                (4 until 14).all { u[it] == 0 } &&
                u[15] == 0x54 &&
                u[14] == 0x02
            ) {
                return METADATA
            }
            return when {
                u[0] == 0xff -> MULTICAST
                u[0] == 0xfe && (u[1] and 0xc0) == 0x80 -> LINK_LOCAL
                u[0] == 0xfe && (u[1] and 0xc0) == 0xc0 -> PRIVATE // deprecated site-local
                (u[0] and 0xfe) == 0xfc -> PRIVATE // fc00::/7 unique-local, incl. fd00::/8
                u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x0d && u[3] == 0xb8 -> RESERVED // documentation
                u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x00 && u[3] == 0x00 -> RESERVED // Teredo 2001::/32
                // local-use NAT64 64:ff9b:1::/48 (RFC 8215): already outside 2000::/3, named for clarity
                u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && u[4] == 0 && u[5] == 1 -> RESERVED
                u[0] == 0x01 && u[1] == 0x00 && (2 until 8).all { u[it] == 0 } -> RESERVED // discard-only
                (u[0] and 0xe0) != 0x20 -> RESERVED // outside 2000::/3 global unicast
                else -> PUBLIC
            }
        }
    }
}
