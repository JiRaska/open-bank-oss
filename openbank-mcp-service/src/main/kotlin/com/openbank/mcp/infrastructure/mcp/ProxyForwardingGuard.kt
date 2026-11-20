// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.Config
import org.jboss.logging.Logger

/**
 * Boot gate for forwarded-header trust (ADR-0383 D5, #16847).
 *
 * Behind the edge the customers tenant's DPoP check needs the EXTERNAL request URL: with
 * `quarkus.oidc.customers.token.authorization-scheme=DPoP`, quarkus-oidc's
 * `BearerAuthenticationMechanism` compares the proof `htu` with `HttpServerRequest.absoluteURI()`
 * (query stripped) BEFORE the request reaches this service's own admission, whose `htu` check is
 * against the static published resource. Quarkus rewrites `absoluteURI()` from forwarded headers
 * when `quarkus.http.proxy.*` is on — and, with `quarkus.http.proxy.trusted-proxies` UNSET, from ANY
 * peer. That is what this refuses at boot.
 *
 * Checked whenever ANY switch in [FORWARDING_SWITCHES] (every Quarkus 3.40 `ProxyConfig` switch that
 * makes request data depend on a peer-supplied header or the PROXY protocol) is on, OR the edge
 * client-address header is configured: the trusted list must be non-empty, every entry a strict IP
 * literal or CIDR (no hostnames), and no prefix shorter than /8 (IPv4) or /16 (IPv6) — which also
 * rejects every trust-all spelling (`0.0.0.0/0`, `0.0.0.0/00`, `::/0`, `0:0:0:0:0:0:0:0/0`) and the
 * split-half pairs (`0.0.0.0/1,128.0.0.0/1`).
 *
 * `@Startup` because `@ApplicationScoped` is lazy and this is a boot gate, not a request check.
 */
@Startup
@ApplicationScoped
class ProxyForwardingGuard @Inject constructor(config: Config) {
    init {
        val on = FORWARDING_SWITCHES.any { config.getOptionalValue(it, Boolean::class.javaObjectType).orElse(false) }
        val edge = config.getOptionalValue(EDGE_HEADER, String::class.java).orElse("").isNotBlank()
        check(on || edge, config.getOptionalValue(TRUSTED_PROXIES, String::class.java).orElse(""))
    }

    companion object {
        private val log = Logger.getLogger(ProxyForwardingGuard::class.java)
        const val TRUSTED_PROXIES = "quarkus.http.proxy.trusted-proxies"
        const val EDGE_HEADER = "mcp.edge.client-address-header"
        private const val MIN_V4_PREFIX = 8
        private const val MIN_V6_PREFIX = 16

        /** quarkus-vertx-http 3.40 `ProxyConfig` switches; `trusted-proxy.*` (named form) is not supported here. */
        val FORWARDING_SWITCHES = listOf(
            "quarkus.http.proxy.proxy-address-forwarding",
            "quarkus.http.proxy.allow-forwarded",
            "quarkus.http.proxy.allow-x-forwarded",
            "quarkus.http.proxy.enable-forwarded-host",
            "quarkus.http.proxy.enable-forwarded-prefix",
            "quarkus.http.proxy.enable-trusted-proxy-header",
            "quarkus.http.proxy.use-proxy-protocol",
        )

        /** Throws when trust is needed without a narrow, literal trusted-proxy list. Visible for the unit test. */
        fun check(needsTrust: Boolean, trustedProxies: String) {
            if (!needsTrust) return
            val entries = trustedProxies.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            check(entries.isNotEmpty()) {
                "forwarded-header trust is configured but $TRUSTED_PROXIES is not set: " +
                    "forwarded headers would be trusted from any peer"
            }
            entries.forEach { entry ->
                val cidr = checkNotNull(IpLiteral.Cidr.parse(entry)) {
                    "$TRUSTED_PROXIES entry is not an IP literal or CIDR (hostnames are refused): $entry"
                }
                val min = if (cidr.isV6) MIN_V6_PREFIX else MIN_V4_PREFIX
                check(cidr.bits >= min) { "$TRUSTED_PROXIES entry is broader than /$min: $entry" }
            }
            log.infof("forwarded headers trusted only from %d configured proxy entries", entries.size)
        }
    }
}
