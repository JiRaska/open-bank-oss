// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The audit-window key for a caller with no token subject (#16847): a hash of the client's
 * NETWORK, not of its exact address.
 *
 * - IPv4 is keyed by the full address (/32).
 * - IPv6 is keyed by its /64 prefix: one client controls a whole /64, so keying by the full
 *   address would let it rotate addresses and earn one audit row per address.
 * - An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) is the IPv4 address it maps.
 *
 * The input is the address Vert.x reports for the request. With `quarkus.http.proxy.*` forwarding
 * on, that is the forwarded client address ONLY when the hop is in
 * `quarkus.http.proxy.trusted-proxies` ([ProxyForwardingGuard]); from any other peer the headers
 * are ignored and this is the peer itself. So this function never reads a header.
 *
 * Only an IP literal is accepted — a hostname is never resolved (no DNS on the request path) and
 * keys as [UNKNOWN], as does a null or unparseable address.
 */
object ClientAddressKey {
    const val UNKNOWN = "ip:unknown"
    private const val IPV6_PREFIX_BYTES = 8
    private const val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
    private val IPV4 = Regex("^($OCTET\\.){3}$OCTET$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$")

    fun of(address: String?): String {
        val literal = address?.trim()?.removePrefix("[")?.removeSuffix("]")
        val bare = literal?.substringBefore('%')
        // A string with ':' is parsed by the JDK as an IPv6 literal, never looked up; an IPv4 literal
        // is matched exactly here first, so nothing that reaches getByName can trigger DNS.
        if (bare.isNullOrEmpty() || !(IPV4.matches(bare) || IPV6.matches(bare))) return UNKNOWN
        val inet = runCatching { InetAddress.getByName(bare) }.getOrNull() ?: return UNKNOWN
        val network = if (inet is Inet6Address) {
            byteArrayOf(V6) + inet.address.copyOf(IPV6_PREFIX_BYTES)
        } else {
            byteArrayOf(V4) + inet.address
        }
        return "ip:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(network))
    }

    private const val V4: Byte = 4
    private const val V6: Byte = 6
}
