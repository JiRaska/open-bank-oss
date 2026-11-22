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
 * Boot gate for edge trust (ADR-0383 D5, #16847; owner decision 2026-11-21: trust the gateway hop
 * by mTLS identity, never by IP).
 *
 * 1. NO Quarkus forwarding, ever: any switch in [FORWARDING_SWITCHES] on, or
 *    `quarkus.http.proxy.trusted-proxies` set at all, refuses to start. Those rewrite
 *    `absoluteURI()`/`remoteAddress()` from peer-supplied headers on EVERY listener, plain HTTP
 *    included. The customers tenant's DPoP `htu` check needs no forwarding: on the TLS listener
 *    Vert.x builds `absoluteURI()` as `https://<Host>/<path>`, and the edge passes the external
 *    Host through unchanged.
 * 2. The edge client-address header ([EDGE_HEADER]) is honoured only over mTLS, so configuring it
 *    requires `quarkus.http.ssl.client-auth=required` AND a pinned gateway SAN ([GATEWAY_IDENTITY]).
 *    A header with no pin, or a pin with client auth off, refuses to start.
 * 3. The customer channel ([CUSTOMER_ENABLED]) is served only on the mTLS listener
 *    ([CustomerMcpEndpoint]), so enabling it with client auth not REQUIRED refuses to start.
 *
 * `@Startup` because `@ApplicationScoped` is lazy and this is a boot gate, not a request check.
 */
@Startup
@ApplicationScoped
class ProxyForwardingGuard @Inject constructor(config: Config) {
    init {
        fun str(key: String) = config.getOptionalValue(key, String::class.java).orElse("").trim()
        check(
            forwarding = FORWARDING_SWITCHES.filter {
                config.getOptionalValue(it, Boolean::class.javaObjectType).orElse(false)
            },
            trustedProxies = str(TRUSTED_PROXIES),
            edgeHeader = str(EDGE_HEADER),
            gatewayIdentity = str(GATEWAY_IDENTITY),
            clientAuth = str(CLIENT_AUTH),
            customerEnabled = config.getOptionalValue(CUSTOMER_ENABLED, Boolean::class.javaObjectType).orElse(false),
        )
    }

    companion object {
        private val log = Logger.getLogger(ProxyForwardingGuard::class.java)
        const val TRUSTED_PROXIES = "quarkus.http.proxy.trusted-proxies"
        const val EDGE_HEADER = "mcp.edge.client-address-header"
        const val GATEWAY_IDENTITY = "mcp.edge.gateway-identity"
        const val CLIENT_AUTH = "quarkus.http.ssl.client-auth"
        const val CUSTOMER_ENABLED = "mcp.customer.enabled"

        /** quarkus-vertx-http 3.40 `ProxyConfig` switches that make request data depend on a peer header. */
        val FORWARDING_SWITCHES = listOf(
            "quarkus.http.proxy.proxy-address-forwarding",
            "quarkus.http.proxy.allow-forwarded",
            "quarkus.http.proxy.allow-x-forwarded",
            "quarkus.http.proxy.enable-forwarded-host",
            "quarkus.http.proxy.enable-forwarded-prefix",
            "quarkus.http.proxy.enable-trusted-proxy-header",
            "quarkus.http.proxy.use-proxy-protocol",
        )

        /** Throws on any config that would trust a header without the gateway's mTLS identity (unit-tested). */
        fun check(
            forwarding: List<String>,
            trustedProxies: String,
            edgeHeader: String,
            gatewayIdentity: String,
            clientAuth: String,
            customerEnabled: Boolean = false,
        ) {
            check(forwarding.isEmpty()) {
                "Quarkus proxy forwarding is refused (edge is trusted by mTLS identity, not IP): $forwarding"
            }
            check(trustedProxies.isEmpty()) { "$TRUSTED_PROXIES must stay unset: the edge is trusted by mTLS identity" }
            check(!customerEnabled || clientAuth.equals("required", ignoreCase = true)) {
                "$CUSTOMER_ENABLED is on but $CLIENT_AUTH is '$clientAuth': the customer root is served only over mTLS"
            }
            if (edgeHeader.isEmpty() && gatewayIdentity.isEmpty()) return
            check(edgeHeader.isNotEmpty() && gatewayIdentity.isNotEmpty()) {
                "$EDGE_HEADER and $GATEWAY_IDENTITY must be configured together"
            }
            check(clientAuth.equals("required", ignoreCase = true)) {
                "$EDGE_HEADER is configured but $CLIENT_AUTH is '$clientAuth', not 'required'"
            }
            log.infof("edge header %s honoured only over mTLS from SAN %s", edgeHeader, gatewayIdentity)
        }
    }
}
