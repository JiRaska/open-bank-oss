// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Boot gate for forwarded-header trust (ADR-0383 D5, #16847).
 *
 * Behind the edge, two things need the EXTERNAL view of the request: the customers tenant's own
 * DPoP check compares the proof's `htu` with the request URL (so the forwarded scheme and host must
 * be applied), and the tokenless audit window keys by client address ([ClientAddressKey]). Quarkus
 * applies `Forwarded` / `X-Forwarded-*` when `quarkus.http.proxy.*` forwarding is on — and, if
 * `quarkus.http.proxy.trusted-proxies` is UNSET, it applies them from ANY peer. That is the state
 * this refuses: any forwarding switch on with no trusted-proxy list, or with a list that trusts
 * every address, stops the service at boot. With a list, Quarkus ignores the headers from every
 * other peer, so a direct caller cannot forge its scheme, host or address.
 *
 * `@Startup` because `@ApplicationScoped` is lazy and this is a boot gate, not a request check.
 */
@Startup
@ApplicationScoped
class ProxyForwardingGuard @Inject constructor(
    @ConfigProperty(name = "quarkus.http.proxy.proxy-address-forwarding") addressForwarding: Optional<Boolean>,
    @ConfigProperty(name = "quarkus.http.proxy.allow-x-forwarded") xForwarded: Optional<Boolean>,
    @ConfigProperty(name = "quarkus.http.proxy.allow-forwarded") forwarded: Optional<Boolean>,
    @ConfigProperty(name = "quarkus.http.proxy.enable-forwarded-host") forwardedHost: Optional<Boolean>,
    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies") trustedProxies: Optional<String>,
) {
    init {
        val on = listOf(addressForwarding, xForwarded, forwarded, forwardedHost).any { it.orElse(false) }
        check(on, trustedProxies.orElse(""))
    }

    companion object {
        private val log = Logger.getLogger(ProxyForwardingGuard::class.java)
        private val TRUST_ALL = setOf("0.0.0.0/0", "::/0", "[::]/0", "*")

        /** Throws when forwarding is on without a narrow trusted-proxy list. Visible for the unit test. */
        fun check(forwardingOn: Boolean, trustedProxies: String) {
            if (!forwardingOn) return
            val entries = trustedProxies.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            check(entries.isNotEmpty()) {
                "quarkus.http.proxy forwarding is on but quarkus.http.proxy.trusted-proxies is not set: " +
                    "forwarded headers would be trusted from any peer"
            }
            check(entries.none { it in TRUST_ALL }) {
                "quarkus.http.proxy.trusted-proxies trusts every address: forwarded headers would be trusted from any peer"
            }
            log.infof("forwarded headers trusted only from %d configured proxy entries", entries.size)
        }
    }
}
