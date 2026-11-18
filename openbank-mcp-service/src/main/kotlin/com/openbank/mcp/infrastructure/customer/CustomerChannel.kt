// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.InMemoryDpopReplayStore
import com.openbank.libs.security.Jose4jDpopProofParser
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.Optional

/**
 * The customer MCP channel switch (ADR-0383 / ADR-0385, #16590). OFF unless
 * `mcp.customer.enabled=true`; with it off, nothing on this channel is reachable and a
 * customers-realm token is refused.
 *
 * `@Startup` on purpose: `@ApplicationScoped` is lazy, and the allowlist check is a BOOT gate
 * (ADR-0385 D1 "mcp-service refuses to boot on one"). With the channel on, a missing issuer, a
 * missing allowlist or a malformed one throws here and the service does not start.
 *
 * The replay store is the bounded single-pod [InMemoryDpopReplayStore]. ADR-0383 P4 accepts that
 * only while no route to mcp-service exists; the shared Valkey store is a prerequisite of opening
 * the route, not of this slice.
 */
@Startup
@ApplicationScoped
class CustomerChannel @Inject constructor(
    @ConfigProperty(name = "mcp.customer.enabled") enabled: Optional<Boolean>,
    @ConfigProperty(name = "mcp.customer.issuer") issuer: Optional<String>,
    @ConfigProperty(name = "mcp.customer.allowlist") allowlistLocation: Optional<String>,
) {
    /** Null when the channel is off; otherwise the admission policy loaded at boot. */
    val admission: CustomerChannelAdmission? = if (enabled.orElse(false)) {
        load(issuer, allowlistLocation)
    } else {
        null
    }

    val enabled: Boolean get() = admission != null

    private companion object {
        private val log = Logger.getLogger(CustomerChannel::class.java)
        private const val CLASSPATH_PREFIX = "classpath:"

        fun load(issuer: Optional<String>, location: Optional<String>): CustomerChannelAdmission {
            val iss = issuer.filter { it.isNotBlank() }.orElseThrow {
                IllegalStateException("mcp.customer.enabled=true but mcp.customer.issuer is not set")
            }
            val where = location.filter { it.isNotBlank() }.orElseThrow {
                IllegalStateException("mcp.customer.enabled=true but mcp.customer.allowlist is not set")
            }
            val text = read(where)
                ?: error("MCP client allowlist not found at '$where'")
            val allowlist = try {
                McpClientAllowlist.parse(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("MCP client allowlist at '$where' is malformed: ${e.message}", e)
            }
            log.infof(
                "customer MCP channel enabled: %d declared client(s), %d enabled, resource %s",
                allowlist.clients.size,
                allowlist.clients.values.count { it.enabled },
                allowlist.resource,
            )
            val verifier = DpopProofVerifier(Jose4jDpopProofParser(), InMemoryDpopReplayStore())
            return CustomerChannelAdmission(iss, allowlist, verifier, Clock.systemUTC())
        }

        private fun read(location: String): String? = if (location.startsWith(CLASSPATH_PREFIX)) {
            val name = location.removePrefix(CLASSPATH_PREFIX).removePrefix("/")
            Thread.currentThread().contextClassLoader.getResourceAsStream(name)
                ?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        } else {
            Path.of(location).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        }
    }
}
