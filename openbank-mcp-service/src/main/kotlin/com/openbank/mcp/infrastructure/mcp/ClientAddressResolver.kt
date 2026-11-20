// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.InetAddress
import java.util.Optional

/**
 * Which client address keys the subject-less audit window (#16847) — deliberately NOT the address
 * Quarkus derives from `X-Forwarded-For`.
 *
 * Quarkus' `proxy-address-forwarding` takes the LEFTMOST `X-Forwarded-For` entry, and an edge that
 * APPENDS (rather than overwrites) leaves that entry under the client's control. So this reads:
 * - the raw TCP peer (`connection().remoteAddress()`, which forwarding never rewrites), unless
 * - `mcp.edge.client-address-header` names ONE edge-set header (e.g. Envoy's
 *   `X-Envoy-External-Address`) AND the peer is in `quarkus.http.proxy.trusted-proxies` AND the
 *   header holds exactly one IP literal. Then that value.
 *
 * Unset header ⇒ no header is ever read. Trusted-proxy entries are IP literals or CIDRs; a hostname
 * entry never matches here (no DNS on the request path).
 */
@ApplicationScoped
class ClientAddressResolver @Inject constructor(
    @ConfigProperty(name = "mcp.edge.client-address-header") private val edgeHeader: Optional<String>,
    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies") trustedProxies: Optional<String>,
) {
    private val trusted: List<Cidr> = trustedProxies.orElse("").split(',').mapNotNull { Cidr.parse(it.trim()) }

    fun key(request: HttpServerRequest): String {
        val peer = runCatching { request.connection()?.remoteAddress()?.hostAddress() }.getOrNull()
        return ClientAddressKey.of(
            resolve(
                peer,
                edgeHeader.orElse("").takeIf {
                    it.isNotBlank()
                }?.let(request::getHeader),
            ),
        )
    }

    /** Visible for the unit test. */
    fun resolve(peer: String?, headerValue: String?): String? {
        if (headerValue == null || edgeHeader.isEmpty) return peer
        val peerIp = ClientAddressKey.literal(peer) ?: return peer
        if (trusted.none { it.contains(peerIp) }) return peer
        val value = headerValue.trim()
        return if (!value.contains(',') && ClientAddressKey.literal(value) != null) value else peer
    }

    internal class Cidr(private val network: ByteArray, private val bits: Int) {
        fun contains(address: InetAddress): Boolean {
            val a = address.address
            if (a.size != network.size) return false
            val full = bits / BYTE
            if ((0 until full).any { a[it] != network[it] }) return false
            val rest = bits % BYTE
            if (rest == 0) return true
            val mask = (MASK_BYTE shl (BYTE - rest)) and MASK_BYTE
            return (a[full].toInt() and mask) == (network[full].toInt() and mask)
        }

        companion object {
            private const val BYTE = 8
            private const val MASK_BYTE = 0xFF

            fun parse(entry: String): Cidr? {
                if (entry.isEmpty()) return null
                val ip = ClientAddressKey.literal(entry.substringBefore('/')) ?: return null
                val max = ip.address.size * BYTE
                val bits = if ('/' in entry) entry.substringAfter('/').toIntOrNull() ?: return null else max
                return if (bits in 0..max) Cidr(ip.address, bits) else null
            }
        }
    }
}
