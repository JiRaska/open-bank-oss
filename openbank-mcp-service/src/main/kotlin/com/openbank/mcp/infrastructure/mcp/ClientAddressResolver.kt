// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.cert.X509Certificate
import java.util.Optional

/**
 * Which client address keys the subject-less audit window (#16847) — deliberately NOT the address
 * Quarkus derives from `X-Forwarded-For`, and never decided by the peer's IP.
 *
 * Owner decision 2026-11-21: the edge hop is trusted by its mTLS IDENTITY, not by address
 * (`quarkus.http.proxy.trusted-proxies` stays unset, [ProxyForwardingGuard] refuses it). So this reads:
 * - the raw TCP peer (`connection().remoteAddress()`), unless
 * - `mcp.edge.client-address-header` names ONE edge-set header (e.g. Envoy's
 *   `X-Envoy-External-Address`) AND the request arrived on the TLS listener (client auth REQUIRED)
 *   AND the verified leaf certificate carries the DNS SAN pinned in `mcp.edge.gateway-identity`
 *   AND the header occurs exactly once holding exactly one strict IP literal. Then that value.
 *
 * Any other fleet-CA certificate (every workload holds one) is a different SAN and is ignored, as
 * is every plain-HTTP request whatever it claims.
 */
@ApplicationScoped
class ClientAddressResolver @Inject constructor(
    @ConfigProperty(name = "mcp.edge.client-address-header") private val edgeHeader: Optional<String>,
    @ConfigProperty(name = "mcp.edge.gateway-identity") gatewayIdentity: Optional<String>,
) {
    private val identity: String? = gatewayIdentity.orElse("").trim().lowercase().takeIf { it.isNotEmpty() }

    private val header: String? = edgeHeader.orElse("").trim().takeIf { it.isNotEmpty() }

    fun key(request: HttpServerRequest): String {
        val peer = runCatching { request.connection()?.remoteAddress()?.hostAddress() }.getOrNull()
        val values = header?.let { name -> runCatching { request.headers().getAll(name).orEmpty() }.getOrNull() }
        return capped(ClientAddressKey.of(resolve(peer, values, fromGateway(request))), System.currentTimeMillis())
    }

    /** True only for a TLS request whose verified peer leaf carries the pinned gateway DNS SAN. */
    fun fromGateway(request: HttpServerRequest): Boolean {
        if (identity == null || !request.isSSL) return false
        val leaf = runCatching { request.connection()?.peerCertificates()?.firstOrNull() }.getOrNull()
        return (leaf as? X509Certificate)?.let { isGateway(it) } ?: false
    }

    /** Visible for the unit test. Only DNS SANs (type 2) count; the CN is never consulted. */
    fun isGateway(leaf: X509Certificate): Boolean {
        val sans = runCatching { leaf.subjectAlternativeNames }.getOrNull() ?: return false
        return sans.any { it.size >= 2 && it[0] == DNS_SAN && (it[1] as? String)?.lowercase() == identity }
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
        private const val DNS_SAN = 2
    }

    /** Visible for the unit test: [headerValues] is every occurrence of the edge header. */
    fun resolve(peer: String?, headerValues: List<String>?, fromGateway: Boolean): String? {
        if (header == null || !fromGateway || headerValues.isNullOrEmpty()) return peer
        val only = headerValues.singleOrNull()?.trim() ?: return peer
        return if (IpLiteral.parse(only) != null) only else peer
    }
}
