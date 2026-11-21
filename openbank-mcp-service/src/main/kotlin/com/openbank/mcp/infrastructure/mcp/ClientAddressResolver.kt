// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
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
        val address = resolve(peer, values)
        return capped(ClientAddressKey.of(address), System.currentTimeMillis(), ClientAddressKey.allocationOf(address))
    }

    /** Distinct subject-less keys admitted in the current minute; above the cap they share [OVERFLOW]. */
    internal var maxKeysPerMinute: Int = MAX_KEYS_PER_MINUTE

    /** Distinct keys one allocation (IPv6 /48, IPv4 /24) may hold per minute; past it they share the allocation key. */
    internal var maxKeysPerAllocation: Int = MAX_KEYS_PER_ALLOCATION
    private var minute = -1L
    private val seen = HashSet<String>()
    private val perAllocation = HashMap<String, Int>()
    private var overflowLogged = false

    /**
     * Bounds the number of distinct subject-less keys per minute (#16847 review): the limiter's
     * window map is pruned per minute, but within one minute a caller cycling /64s (or a botnet)
     * could otherwise mint unbounded entries.
     *
     * Two levels, because one party cheaply holds a whole IPv6 /48 (65 536 /64s): past
     * [maxKeysPerAllocation] distinct keys an allocation's further networks share ONE key for that
     * allocation — it never reaches the global budget, so it cannot push other callers into
     * [OVERFLOW] and suppress their audit rows. Only callers spread over many allocations can reach
     * the global cap; the first overflow of each minute is logged, so that state is never silent.
     */
    @Synchronized
    internal fun capped(key: String, nowMillis: Long, allocation: String = key): String {
        val m = nowMillis / MILLIS_PER_MINUTE
        if (m != minute) {
            minute = m
            seen.clear()
            perAllocation.clear()
            overflowLogged = false
        }
        if (key in seen) return key
        val held = perAllocation[allocation] ?: 0
        val admitted = if (held >= maxKeysPerAllocation) allocation else key
        if (admitted in seen) return admitted
        if (seen.size >= maxKeysPerMinute) {
            if (!overflowLogged) {
                overflowLogged = true
                log.warnf(
                    "subject-less audit key budget (%d/min) exhausted; further new networks share %s",
                    maxKeysPerMinute,
                    OVERFLOW,
                )
            }
            return OVERFLOW
        }
        seen += admitted
        if (admitted == key) perAllocation[allocation] = held + 1
        return admitted
    }

    companion object {
        const val OVERFLOW = "ip:overflow"
        const val MAX_KEYS_PER_MINUTE = 4096
        const val MAX_KEYS_PER_ALLOCATION = 64
        private val log = Logger.getLogger(ClientAddressResolver::class.java)
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
