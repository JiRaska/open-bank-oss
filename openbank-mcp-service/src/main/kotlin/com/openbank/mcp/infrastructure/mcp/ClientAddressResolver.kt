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
        return capped(
            ClientAddressKey.of(address),
            System.currentTimeMillis(),
            ClientAddressKey.allocationOf(address),
            ClientAddressKey.regionOf(address),
        )
    }

    /** Distinct subject-less keys admitted in the current minute; above the cap they share [OVERFLOW]. */
    internal var maxKeysPerMinute: Int = MAX_KEYS_PER_MINUTE

    /** Distinct keys one allocation (IPv6 /48, IPv4 /24) may hold per minute; past it they share the allocation key. */
    internal var maxKeysPerAllocation: Int = MAX_KEYS_PER_ALLOCATION

    /** Distinct keys one region (IPv6 /32) may hold per minute, allocation keys included; past it they share the region key. */
    internal var maxKeysPerRegion: Int = MAX_KEYS_PER_REGION

    /** How many times the overflow WARN has been written (one per minute at most); visible for the unit test. */
    internal var overflowWarnings: Int = 0
    private var minute = -1L
    private val seen = HashSet<String>()
    private val perAllocation = HashMap<String, Int>()
    private val perRegion = HashMap<String, Int>()
    private var overflowLogged = false

    /**
     * Bounds the number of distinct subject-less keys per minute (#16847 review): the limiter's
     * window map is pruned per minute, but within one minute a caller cycling /64s (or a botnet)
     * could otherwise mint unbounded entries.
     *
     * Three tiers, because one party cheaply holds many networks: one IPv6 /48 is 65 536 /64s and
     * one /32 is 65 536 /48s. Past [maxKeysPerAllocation] distinct keys an allocation (/48, IPv4 /24)
     * shares ONE allocation key; past [maxKeysPerRegion] keys (allocation keys included) a /32 shares
     * ONE region key. So one allocation admits at most 65 keys a minute and one /32 at most 257;
     * filling the [maxKeysPerMinute] budget of 4096 — and pushing other callers into [OVERFLOW],
     * which suppresses their audit rows — needs at least 16 distinct /32s (RIR scale) or a spread
     * of IPv4 /24s. The first overflow of each minute is logged, so that state is never silent.
     *
     * Tradeoff: once an allocation (or region) has spent its slots, a NEW neighbour in it shares
     * that allocation's (region's) key — and so its one-row-per-minute audit window for refused
     * calls ([McpCustomerBranch]) — for the rest of the minute (at most 60 s). Neighbours already
     * admitted that minute keep their own key. This key feeds only that audit window, not a rate limit.
     */
    @Synchronized
    internal fun capped(key: String, nowMillis: Long, allocation: String = key, region: String = allocation): String {
        val m = nowMillis / MILLIS_PER_MINUTE
        if (m != minute) {
            minute = m
            seen.clear()
            perAllocation.clear()
            perRegion.clear()
            overflowLogged = false
        }
        if (key in seen) return key
        val allocationHeld = perAllocation[allocation] ?: 0
        var admitted = if (allocationHeld >= maxKeysPerAllocation) allocation else key
        if (admitted in seen) return admitted
        val regionHeld = perRegion[region] ?: 0
        if (regionHeld >= maxKeysPerRegion) admitted = region
        if (admitted in seen) return admitted
        if (seen.size >= maxKeysPerMinute) return overflow()
        seen += admitted
        if (admitted == key) perAllocation[allocation] = allocationHeld + 1
        if (admitted != region) perRegion[region] = regionHeld + 1
        return admitted
    }

    private fun overflow(): String {
        if (!overflowLogged) {
            overflowLogged = true
            overflowWarnings++
            log.warnf(
                "subject-less audit key budget (%d/min) exhausted; further new networks share %s",
                maxKeysPerMinute,
                OVERFLOW,
            )
        }
        return OVERFLOW
    }

    companion object {
        const val OVERFLOW = "ip:overflow"
        const val MAX_KEYS_PER_MINUTE = 4096
        const val MAX_KEYS_PER_ALLOCATION = 64
        const val MAX_KEYS_PER_REGION = 256
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
