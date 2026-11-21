// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
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
 *   header occurs exactly once holding exactly one strict IP literal. Then that value.
 *
 * Unset header ⇒ no header is ever read. Trusted-proxy entries are parsed by [IpLiteral.Cidr];
 * a hostname entry never matches (and [ProxyForwardingGuard] refuses to boot on one).
 */
@ApplicationScoped
class ClientAddressResolver @Inject constructor(
    @ConfigProperty(name = "mcp.edge.client-address-header") private val edgeHeader: Optional<String>,
    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies") trustedProxies: Optional<String>,
) {
    private val trusted: List<IpLiteral.Cidr> =
        trustedProxies.orElse("").split(',').mapNotNull { IpLiteral.Cidr.parse(it) }

    private val header: String? = edgeHeader.orElse("").trim().takeIf { it.isNotEmpty() }

    fun key(request: HttpServerRequest): String {
        val peer = runCatching { request.connection()?.remoteAddress()?.hostAddress() }.getOrNull()
        val values = header?.let { name -> runCatching { request.headers().getAll(name).orEmpty() }.getOrNull() }
        return capped(ClientAddressKey.of(resolve(peer, values)), System.currentTimeMillis())
    }

    /** Distinct subject-less keys admitted in the current minute; above the cap they share [OVERFLOW]. */
    internal var maxKeysPerMinute: Int = MAX_KEYS_PER_MINUTE
    private var minute = -1L
    private val seen = HashSet<String>()

    /**
     * Bounds the number of distinct subject-less keys per minute (#16847 review): the limiter's
     * window map is pruned per minute, but within one minute a caller cycling /64s (or a botnet)
     * could otherwise mint unbounded entries. Past the cap every NEW key collapses into one shared
     * key, so memory is bounded and the overflow still gets its one audit row per window.
     */
    @Synchronized
    internal fun capped(key: String, nowMillis: Long): String {
        val m = nowMillis / MILLIS_PER_MINUTE
        if (m != minute) {
            minute = m
            seen.clear()
        }
        if (key in seen) return key
        if (seen.size >= maxKeysPerMinute) return OVERFLOW
        seen += key
        return key
    }

    companion object {
        const val OVERFLOW = "ip:overflow"
        const val MAX_KEYS_PER_MINUTE = 4096
        private const val MILLIS_PER_MINUTE = 60_000L
    }

    /** Visible for the unit test: [headerValues] is every occurrence of the edge header. */
    fun resolve(peer: String?, headerValues: List<String>?): String? {
        if (header == null || headerValues.isNullOrEmpty()) return peer
        val peerIp = IpLiteral.parse(peer) ?: return peer
        if (trusted.none { it.contains(peerIp) }) return peer
        val only = headerValues.singleOrNull()?.trim() ?: return peer
        return if (IpLiteral.parse(only) != null) only else peer
    }
}
